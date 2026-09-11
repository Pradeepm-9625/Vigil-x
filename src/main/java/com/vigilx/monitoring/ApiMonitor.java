package com.vigilx.monitoring;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.SecretMasker;

/**
 * Passive, reusable observability layer for API/XHR traffic.
 *
 * <p>Records every API response whose status is not 200/201/204, every request that fails outright
 * (connection refused, aborted, DNS), and enough per-request state for {@link PageApiTracker} to
 * decide when a page's API work has finished. It never throws into the automation: every public
 * entry point swallows its own failures, so a defect here cannot break a running test.
 *
 * <p>Attach at the {@link BrowserContext} level where possible - that covers popups and any extra
 * page the application opens, which a page-level listener misses.
 *
 * <p>State is static and thread-safe so a whole soak execution accumulates into one report. The
 * current page/operation/iteration labels are process-wide, which suits this framework's single
 * shared {@code PlaywrightFactory} page; see the limitations note in the class docs of
 * {@link PageApiTracker} for parallel execution.
 */
public final class ApiMonitor {

    /** Statuses treated as successful; everything else is recorded. */
    private static final Set<Integer> EXPECTED_STATUSES = Set.of(200, 201, 204);

    /** Codes always listed in the summary, even when their count is zero. */
    private static final int[] SUMMARY_STATUSES =
            {400, 401, 403, 404, 405, 408, 409, 422, 429, 500, 501, 502, 503, 504};

    /** Resource types considered API traffic. Static assets are ignored. */
    private static final Set<String> API_RESOURCE_TYPES = Set.of("xhr", "fetch", "eventsource", "websocket");

    /** Long-lived transports that must never hold up a page-complete decision. */
    private static final Set<String> STREAMING_RESOURCE_TYPES = Set.of("websocket", "eventsource", "media");

    /** Extensions that identify a static asset even when the resource type is unhelpful. */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".js", ".mjs", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".bmp",
            ".woff", ".woff2", ".ttf", ".otf", ".eot", ".map", ".mp4", ".webm", ".m4s", ".ts");

    /** URL fragments that mark API traffic that the browser did not classify as xhr/fetch. */
    private static final String[] API_URL_HINTS = {"/api/", "/api?", "/v1/", "/v2/", "/graphql", "/rest/"};

    /** URL fragments for media/streaming transports excluded from page-complete gating. */
    private static final String[] STREAMING_URL_HINTS = {".m3u8", ".mpd", "/hls", "/webrtc", "/stream", "/ws"};

    /**
     * Background pollers that run continuously regardless of which page is open. They must not gate
     * page completion or be reported as page timeouts - but their responses are still checked, so a
     * failing health endpoint is still recorded. Override with {@code api.monitor.polling.urls}.
     */
    private static final String DEFAULT_POLLING_URLS =
            "/health,/heartbeat,/ping,/keep-alive,/notifications,/unread";

    private static final String SEPARATOR = "============================================================";
    private static final String SUB_SEPARATOR = "------------------------------------------------------------";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String UNREADABLE_BODY = "<Unable to read response body>";

    private static final String DEFAULT_OUTPUT_DIRECTORY = "target/soak-test/api-failures";
    private static final String LOG_FILE_NAME = "api-failures.log";
    private static final int MAX_BODY_CHARACTERS = 2000;
    private static final int MAX_STORED_FAILURES = 5000;

    /**
     * Full per-call API inventory - separate from the failure log above, and off by default.
     * Existing SOAK behavior, output, and the failure log are completely unaffected by this
     * feature whether it is enabled or not; see {@code api.inventory.enabled}.
     */
    private static final String DEFAULT_INVENTORY_OUTPUT_DIRECTORY = "target/apisecurity/api-inventory";
    private static final String INVENTORY_FILE_NAME = "api-inventory.json";
    private static final int MAX_INVENTORY_ENTRIES = 5000;
    private static final int MAX_INVENTORY_BODY_CHARACTERS = 2000;
    private static final ObjectMapper INVENTORY_JSON = new ObjectMapper();

    /** A request in flight longer than this is treated as streaming and stops gating the page. */
    private static final long STREAMING_THRESHOLD_MS = 20000;

    /** Insertion-ordered failures, one entry per distinct iteration+page+method+status+url. */
    private static final ConcurrentLinkedQueue<ApiFailure> FAILURES = new ConcurrentLinkedQueue<>();

    /** Dedup index over the same entries. */
    private static final ConcurrentHashMap<String, ApiFailure> FAILURES_BY_KEY = new ConcurrentHashMap<>();

    /** Distinct failures dropped once MAX_STORED_FAILURES was reached. */
    private static final AtomicInteger DROPPED = new AtomicInteger();

    /** Every API request currently awaiting a response, with the nanotime it started. */
    private static final ConcurrentHashMap<Request, Long> IN_FLIGHT = new ConcurrentHashMap<>();

    /** Count of API responses seen per HTTP status, for the closing summary. */
    private static final ConcurrentHashMap<Integer, AtomicInteger> STATUS_COUNTS = new ConcurrentHashMap<>();

    /** Total API responses observed (successful and failed). */
    private static final AtomicInteger TOTAL_RESPONSES = new AtomicInteger();

    /** Requests that never completed before their page's deadline. */
    private static final AtomicInteger TIMEOUTS = new AtomicInteger();

    /** Completed page validations, for the closing summary. */
    private static final ConcurrentLinkedQueue<PageApiResult> PAGE_RESULTS = new ConcurrentLinkedQueue<>();

    /**
     * Insertion-ordered API inventory - every call, success or failure - recorded only when
     * {@code api.inventory.enabled=true}. Feeds the separate API security/performance automation;
     * the existing failure log (FAILURES above) is built independently of this and unaffected.
     */
    private static final ConcurrentLinkedQueue<ApiInventoryEntry> INVENTORY = new ConcurrentLinkedQueue<>();

    /** Dedup index over the inventory, keyed by exact method+host+path+query (not path-templated). */
    private static final ConcurrentHashMap<String, ApiInventoryEntry> INVENTORY_BY_KEY = new ConcurrentHashMap<>();

    /** Distinct inventory entries dropped once MAX_INVENTORY_ENTRIES was reached. */
    private static final AtomicInteger INVENTORY_DROPPED = new AtomicInteger();

    /** Pages/contexts already carrying listeners, so a re-attach cannot double-count. */
    private static final Set<Page> ATTACHED_PAGES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<BrowserContext> ATTACHED_CONTEXTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();

    /** Labels stamped onto each failure. Written by the test thread, read by the dispatcher thread. */
    private static volatile String currentPage = "-";
    private static volatile String currentOperation = "-";
    private static volatile int currentIteration = 1;

    private ApiMonitor() {
    }

    /** One recorded failure. Immutable except for its occurrence counter. */
    public static final class ApiFailure {
        private final String timestamp;
        private final String method;
        private final int status;
        private final String statusText;
        private final String url;
        private final String resourceType;
        private final String responseBody;
        private final String pageName;
        private final String operation;
        private final int iteration;
        private final String error;
        private final AtomicInteger occurrences = new AtomicInteger(1);

        private ApiFailure(String timestamp, String method, int status, String statusText, String url,
                           String resourceType, String responseBody, String pageName, String operation,
                           int iteration, String error) {
            this.timestamp = timestamp;
            this.method = method;
            this.status = status;
            this.statusText = statusText;
            this.url = url;
            this.resourceType = resourceType;
            this.responseBody = responseBody;
            this.pageName = pageName;
            this.operation = operation;
            this.iteration = iteration;
            this.error = error;
        }

        public String timestamp() { return timestamp; }
        public String method() { return method; }
        public int status() { return status; }
        public String statusText() { return statusText; }
        public String url() { return url; }
        public String resourceType() { return resourceType; }
        public String responseBody() { return responseBody; }
        public String pageName() { return pageName; }
        public String operation() { return operation; }
        public int iteration() { return iteration; }
        public String error() { return error; }
        public int occurrences() { return occurrences.get(); }
    }

    /** Outcome of one page's API validation. */
    public static final class PageApiResult {
        private final String pageName;
        private final int iteration;
        private final int requests;
        private final int completed;
        private final int successful;
        private final int failed;
        private final int timeouts;
        private final List<String> failedApis;
        private final List<String> pendingApis;

        PageApiResult(String pageName, int iteration, int requests, int completed, int successful,
                      int failed, int timeouts, List<String> failedApis, List<String> pendingApis) {
            this.pageName = pageName;
            this.iteration = iteration;
            this.requests = requests;
            this.completed = completed;
            this.successful = successful;
            this.failed = failed;
            this.timeouts = timeouts;
            this.failedApis = failedApis;
            this.pendingApis = pendingApis;
        }

        public String pageName() { return pageName; }
        public int iteration() { return iteration; }
        public int requests() { return requests; }
        public int completed() { return completed; }
        public int successful() { return successful; }
        public int failed() { return failed; }
        public int timeouts() { return timeouts; }
        public List<String> failedApis() { return failedApis; }
        public List<String> pendingApis() { return pendingApis; }

        /** True when no API failed and nothing timed out. */
        public boolean isPassed() {
            return failed == 0 && timeouts == 0;
        }

        /** The block printed to the console after each page. */
        public String render() {
            StringBuilder text = new StringBuilder();
            text.append(SEPARATOR).append(System.lineSeparator())
                    .append("PAGE API VALIDATION").append(System.lineSeparator())
                    .append(SEPARATOR).append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("Page            : ").append(pageName).append(System.lineSeparator())
                    .append("Iteration       : ").append(iteration).append(System.lineSeparator())
                    .append("API Requests    : ").append(requests).append(System.lineSeparator())
                    .append("Completed       : ").append(completed).append(System.lineSeparator())
                    .append("Successful      : ").append(successful).append(System.lineSeparator())
                    .append("Failed          : ").append(failed).append(System.lineSeparator())
                    .append("Timeouts        : ").append(timeouts).append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("RESULT          : ").append(isPassed() ? "PASS" : "FAIL").append(System.lineSeparator());
            if (!failedApis.isEmpty()) {
                text.append(System.lineSeparator()).append("Failed API:").append(System.lineSeparator());
                for (String failure : failedApis) {
                    text.append(failure).append(System.lineSeparator());
                }
            }
            if (!pendingApis.isEmpty()) {
                text.append(System.lineSeparator()).append("Timed out / still pending:").append(System.lineSeparator());
                for (String pending : pendingApis) {
                    text.append(pending).append(System.lineSeparator());
                }
            }
            text.append(SEPARATOR).append(System.lineSeparator());
            return text.toString();
        }
    }

    /**
     * One captured API call - success or failure alike - recorded only when the opt-in
     * {@code api.inventory.enabled} flag is on. Headers and bodies are always masked via
     * {@link SecretMasker} before they reach this class.
     */
    public static final class ApiInventoryEntry {
        private final String timestamp;
        private final String method;
        private final String host;
        private final String path;
        private final String query;
        private final int status;
        private final Long responseTimeMs;
        private final String contentType;
        private final Map<String, String> requestHeaders;
        private final Map<String, String> responseHeaders;
        private final String requestBody;
        private final String responseBody;
        private final String resourceType;
        private final String pageName;
        private final String operation;
        private final int iteration;
        private final AtomicInteger occurrences = new AtomicInteger(1);

        private ApiInventoryEntry(String timestamp, String method, String host, String path, String query,
                                  int status, Long responseTimeMs, String contentType,
                                  Map<String, String> requestHeaders, Map<String, String> responseHeaders,
                                  String requestBody, String responseBody, String resourceType,
                                  String pageName, String operation, int iteration) {
            this.timestamp = timestamp;
            this.method = method;
            this.host = host;
            this.path = path;
            this.query = query;
            this.status = status;
            this.responseTimeMs = responseTimeMs;
            this.contentType = contentType;
            this.requestHeaders = requestHeaders;
            this.responseHeaders = responseHeaders;
            this.requestBody = requestBody;
            this.responseBody = responseBody;
            this.resourceType = resourceType;
            this.pageName = pageName;
            this.operation = operation;
            this.iteration = iteration;
        }

        public String timestamp() { return timestamp; }
        public String method() { return method; }
        public String host() { return host; }
        public String path() { return path; }
        public String query() { return query; }
        public int status() { return status; }
        public Long responseTimeMs() { return responseTimeMs; }
        public String contentType() { return contentType; }
        public Map<String, String> requestHeaders() { return requestHeaders; }
        public Map<String, String> responseHeaders() { return responseHeaders; }
        public String requestBody() { return requestBody; }
        public String responseBody() { return responseBody; }
        public String resourceType() { return resourceType; }
        public String pageName() { return pageName; }
        public String operation() { return operation; }
        public int iteration() { return iteration; }
        public int occurrences() { return occurrences.get(); }

        /** Plain-map shape for JSON output; avoids relying on Jackson bean-introspection of this class. */
        public Map<String, Object> toJsonMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", timestamp);
            map.put("method", method);
            map.put("host", host);
            map.put("path", path);
            map.put("query", query == null ? "" : query);
            map.put("statusCode", status);
            map.put("responseTimeMs", responseTimeMs);
            map.put("contentType", contentType == null ? "" : contentType);
            map.put("requestHeaders", requestHeaders);
            map.put("responseHeaders", responseHeaders);
            map.put("requestBody", requestBody == null ? "" : requestBody);
            map.put("responseBody", responseBody == null ? "" : responseBody);
            map.put("resourceType", resourceType);
            map.put("pageName", pageName);
            map.put("operation", operation);
            map.put("iteration", iteration);
            map.put("occurrences", occurrences());
            return map;
        }
    }

    // ---------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------

    /**
     * Registers listeners on a whole browser context, covering every page it owns including popups
     * opened later. Preferred over {@link #attach(Page)}. Never throws.
     */
    public static void attach(BrowserContext context) {
        try {
            if (context == null || !isEnabled() || !ATTACHED_CONTEXTS.add(context)) {
                return;
            }
            context.onRequest(ApiMonitor::onRequest);
            context.onResponse(ApiMonitor::record);
            context.onRequestFailed(ApiMonitor::onRequestFailed);
            registerShutdownHook();
            System.out.println("[API MONITOR] Attached to browser context (covers popups and new pages).");
        } catch (Exception exception) {
            System.err.println("[API MONITOR] Could not attach context listeners: " + exception.getMessage());
        }
    }

    /**
     * Registers listeners on a single page. Retained for callers that only have a page; prefer the
     * {@link BrowserContext} overload. Safe to call repeatedly with the same page. Never throws.
     */
    public static void attach(Page page) {
        try {
            if (page == null || !isEnabled() || !ATTACHED_PAGES.add(page)) {
                return;
            }
            page.onRequest(ApiMonitor::onRequest);
            page.onResponse(ApiMonitor::record);
            page.onRequestFailed(ApiMonitor::onRequestFailed);
            registerShutdownHook();
        } catch (Exception exception) {
            System.err.println("[API MONITOR] Could not attach response listener: " + exception.getMessage());
        }
    }

    /** Writes the consolidated report on JVM exit even if teardown never runs. */
    private static void registerShutdownHook() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                writeReportQuietly();
                // No-op unless api.inventory.enabled=true; the existing failure report above is
                // written first and is completely unaffected either way.
                writeInventoryReportQuietly();
            }, "api-monitor-report"));
        } catch (Exception ignored) {
            // A JVM already shutting down rejects new hooks; teardown still covers the normal path.
        }
    }

    // ---------------------------------------------------------------------
    // Context labels
    // ---------------------------------------------------------------------

    /** Labels every subsequent failure with the page being validated. */
    public static void setCurrentPage(String pageName) {
        currentPage = pageName == null || pageName.isBlank() ? "-" : pageName;
    }

    /** Labels every subsequent failure with the operation in progress. */
    public static void setCurrentOperation(String operation) {
        currentOperation = operation == null || operation.isBlank() ? "-" : operation;
    }

    /** Labels every subsequent failure with the soak iteration number. */
    public static void setIteration(int iteration) {
        currentIteration = iteration;
    }

    public static int getIteration() {
        return currentIteration;
    }

    // ---------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------

    /** Tracks an API request as in flight so a page can wait for it. */
    private static void onRequest(Request request) {
        try {
            String url = request.url();
            String resourceType = safeResourceType(request);
            // Streaming transports and background pollers never gate a page: they are always in
            // flight, so waiting on them would time out every page that happens to be open.
            if (isApiTraffic(resourceType, url) && !isStreaming(resourceType, url) && !isBackgroundPolling(url)) {
                IN_FLIGHT.put(request, System.nanoTime());
            }
        } catch (Exception ignored) {
            // Never let bookkeeping disturb the run.
        }
    }

    /**
     * Records a request that never produced a response - connection refused, aborted, DNS failure.
     * These are invisible to a response-only listener.
     */
    private static void onRequestFailed(Request request) {
        try {
            IN_FLIGHT.remove(request);
            String resourceType = safeResourceType(request);
            String url = request.url();
            if (!isApiTraffic(resourceType, url)) {
                return;
            }
            String failure;
            try {
                failure = request.failure();
            } catch (Exception exception) {
                failure = "request failed";
            }
            // Streaming transports are expected to be cut when a page closes; not a defect.
            if (isStreaming(resourceType, url)) {
                return;
            }

            // net::ERR_ABORTED is what the browser reports for any in-flight request cancelled by a
            // navigation - long-polls especially. Recording those as defects floods the report with
            // false positives, so they are skipped unless explicitly requested.
            if (failure != null && failure.contains("ERR_ABORTED") && !recordAbortedRequests()) {
                return;
            }
            store(safeMethod(request), 0, "REQUEST FAILED", url, resourceType,
                    "<no response>", failure == null ? "request failed" : failure);
        } catch (Exception exception) {
            System.err.println("[API MONITOR] Skipped a failed request: " + exception.getMessage());
        }
    }

    /** Handles one response. Any failure here is swallowed so monitoring continues afterwards. */
    private static void record(Response response) {
        try {
            Request request = response.request();
            Long startedNanos = IN_FLIGHT.remove(request);

            int status = response.status();
            String url = response.url();
            String resourceType = safeResourceType(request);
            if (!isApiTraffic(resourceType, url)) {
                return;
            }

            TOTAL_RESPONSES.incrementAndGet();
            STATUS_COUNTS.computeIfAbsent(status, key -> new AtomicInteger()).incrementAndGet();

            if (EXPECTED_STATUSES.contains(status)) {
                // The full inventory (opt-in, off by default) wants every call, not just failures;
                // the failure log below only ever sees non-2xx/204 responses, exactly as before.
                recordInventoryIfEnabled(request, response, status, resourceType, startedNanos, null);
                return;
            }

            // The body is read only for responses already known to be failures, keeping this off the
            // hot path for the thousands of successful calls a soak makes.
            String body = readBodySafely(response);
            store(safeMethod(request), status, safeStatusText(response), url, resourceType, body, null);
            recordInventoryIfEnabled(request, response, status, resourceType, startedNanos, body);

        } catch (Exception exception) {
            System.err.println("[API MONITOR] Skipped a response: " + exception.getMessage());
        }
    }

    /**
     * Adds one call to the full API inventory when {@code api.inventory.enabled=true}; a no-op
     * otherwise. Completely separate from - and never influences - the failure log above.
     *
     * @param bodyAlreadyRead the response body if the caller already read it (failures), otherwise
     *                        {@code null} so this method reads it itself, only when enabled
     */
    private static void recordInventoryIfEnabled(Request request, Response response, int status,
                                                  String resourceType, Long startedNanos, String bodyAlreadyRead) {
        if (!isInventoryEnabled()) {
            return;
        }
        try {
            String method = safeMethod(request);
            URI uri = URI.create(response.url());
            String host = uri.getHost() == null ? "" : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            String query = uri.getQuery();

            String key = method + " " + host + path + "?" + (query == null ? "" : query);
            ApiInventoryEntry existing = INVENTORY_BY_KEY.get(key);
            if (existing != null) {
                existing.occurrences.incrementAndGet();
                return;
            }
            if (INVENTORY_BY_KEY.size() >= maxInventoryEntries()) {
                INVENTORY_DROPPED.incrementAndGet();
                return;
            }

            Long responseTimeMs = startedNanos == null ? null : (System.nanoTime() - startedNanos) / 1_000_000;
            String body = bodyAlreadyRead != null ? bodyAlreadyRead : readBodySafely(response);
            Map<String, String> responseHeaders = safeResponseHeaders(response);

            ApiInventoryEntry entry = new ApiInventoryEntry(
                    LocalDateTime.now().format(TIMESTAMP), method, host, path, query, status, responseTimeMs,
                    responseHeaders.getOrDefault("content-type", ""),
                    SecretMasker.maskHeaders(safeRequestHeaders(request)),
                    SecretMasker.maskHeaders(responseHeaders),
                    SecretMasker.maskBody(safeRequestBody(request), MAX_INVENTORY_BODY_CHARACTERS),
                    SecretMasker.maskBody(body, MAX_INVENTORY_BODY_CHARACTERS),
                    resourceType, currentPage, currentOperation, currentIteration);

            ApiInventoryEntry raced = INVENTORY_BY_KEY.putIfAbsent(key, entry);
            if (raced == null) {
                INVENTORY.add(entry);
            } else {
                raced.occurrences.incrementAndGet();
            }
        } catch (Exception exception) {
            System.err.println("[API MONITOR] Skipped an inventory entry: " + exception.getMessage());
        }
    }

    private static Map<String, String> safeRequestHeaders(Request request) {
        try {
            Map<String, String> headers = request.headers();
            return headers == null ? Map.of() : headers;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static Map<String, String> safeResponseHeaders(Response response) {
        try {
            Map<String, String> headers = response.headers();
            return headers == null ? Map.of() : headers;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static String safeRequestBody(Request request) {
        try {
            String data = request.postData();
            return data == null ? "" : data;
        } catch (Exception exception) {
            return "";
        }
    }

    /** Deduplicates within an iteration+page, then stores the failure. */
    private static void store(String method, int status, String statusText, String url,
                              String resourceType, String body, String error) {
        String page = currentPage;
        String operation = currentOperation;
        int iteration = currentIteration;

        // Iteration and page are part of the key so a repeat in a later soak cycle is its own entry,
        // while a repeat within the same page only bumps the occurrence counter.
        String key = iteration + "|" + page + "|" + method + " " + status + " " + url;

        ApiFailure known = FAILURES_BY_KEY.get(key);
        if (known != null) {
            known.occurrences.incrementAndGet();
            return;
        }
        if (FAILURES_BY_KEY.size() >= MAX_STORED_FAILURES) {
            DROPPED.incrementAndGet();
            return;
        }

        ApiFailure failure = new ApiFailure(LocalDateTime.now().format(TIMESTAMP), method, status,
                statusText, url, resourceType, body, page, operation, iteration, error);

        ApiFailure raced = FAILURES_BY_KEY.putIfAbsent(key, failure);
        if (raced == null) {
            FAILURES.add(failure);
        } else {
            raced.occurrences.incrementAndGet();
        }
    }

    /** True for XHR/fetch traffic, or for a URL that looks like an API despite its resource type. */
    private static boolean isApiTraffic(String resourceType, String url) {
        if (API_RESOURCE_TYPES.contains(resourceType)) {
            return true;
        }
        String path = url.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        String withoutQuery = query < 0 ? path : path.substring(0, query);
        for (String extension : STATIC_EXTENSIONS) {
            if (withoutQuery.endsWith(extension)) {
                return false;
            }
        }
        for (String hint : API_URL_HINTS) {
            if (path.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for endpoints that poll continuously in the background. Their responses are still
     * evaluated for failures; they are only excluded from page-completion waiting.
     */
    private static boolean isBackgroundPolling(String url) {
        try {
            String path = url.toLowerCase(Locale.ROOT);
            String configured = ConfigReader.getOrDefault("api.monitor.polling.urls", DEFAULT_POLLING_URLS);
            for (String hint : configured.split(",")) {
                String trimmed = hint.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && path.contains(trimmed)) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    /** True for long-lived transports that must not gate page completion. */
    private static boolean isStreaming(String resourceType, String url) {
        if (STREAMING_RESOURCE_TYPES.contains(resourceType)) {
            return true;
        }
        String path = url.toLowerCase(Locale.ROOT);
        for (String hint : STREAMING_URL_HINTS) {
            if (path.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String readBodySafely(Response response) {
        try {
            String body = response.text();
            if (body == null || body.isBlank()) {
                return "<empty response body>";
            }
            body = body.strip();
            if (body.length() > MAX_BODY_CHARACTERS) {
                return body.substring(0, MAX_BODY_CHARACTERS) + " ...[truncated]";
            }
            return body;
        } catch (Exception exception) {
            // Bodies are unavailable for redirects, aborted requests and closed pages: expected.
            return UNREADABLE_BODY;
        }
    }

    private static String safeMethod(Request request) {
        try {
            return request == null ? "UNKNOWN" : request.method();
        } catch (Exception exception) {
            return "UNKNOWN";
        }
    }

    private static String safeResourceType(Request request) {
        try {
            String type = request == null ? null : request.resourceType();
            return type == null ? "unknown" : type.toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static String safeStatusText(Response response) {
        try {
            String text = response.statusText();
            return text == null || text.isBlank() ? "-" : text;
        } catch (Exception exception) {
            return "-";
        }
    }

    // ---------------------------------------------------------------------
    // Page-completion support (used by PageApiTracker)
    // ---------------------------------------------------------------------

    /** API requests still awaiting a response, excluding anything now judged to be streaming. */
    public static int pendingApiCount() {
        return pendingApiUrls().size();
    }

    /** URLs of the still-pending API requests, newest state each call. */
    public static List<String> pendingApiUrls() {
        List<String> pending = new ArrayList<>();
        long now = System.nanoTime();
        for (Map.Entry<Request, Long> entry : IN_FLIGHT.entrySet()) {
            try {
                long elapsedMs = (now - entry.getValue()) / 1_000_000;
                if (elapsedMs > STREAMING_THRESHOLD_MS) {
                    // Long-lived: stop gating on it, but leave it tracked for the timeout report.
                    continue;
                }
                pending.add(entry.getKey().method() + " " + entry.getKey().url());
            } catch (Exception ignored) {
                // A disposed request is no longer interesting.
            }
        }
        return pending;
    }

    /** Requests in flight longer than the streaming threshold, reported as timeouts by a page. */
    public static List<String> stalledApiUrls() {
        List<String> stalled = new ArrayList<>();
        long now = System.nanoTime();
        for (Map.Entry<Request, Long> entry : IN_FLIGHT.entrySet()) {
            try {
                long elapsedMs = (now - entry.getValue()) / 1_000_000;
                if (elapsedMs > STREAMING_THRESHOLD_MS) {
                    stalled.add(entry.getKey().method() + " " + entry.getKey().url()
                            + " (in flight " + elapsedMs + "ms)");
                }
            } catch (Exception ignored) {
                // Ignore disposed requests.
            }
        }
        return stalled;
    }

    /** Total API responses observed so far, successful and failed. */
    public static int getTotalResponses() {
        return TOTAL_RESPONSES.get();
    }

    /** Records a page's outcome for the closing summary. */
    public static void recordPageResult(PageApiResult result) {
        if (result != null) {
            PAGE_RESULTS.add(result);
            TIMEOUTS.addAndGet(result.timeouts());
        }
    }

    public static List<PageApiResult> getPageResults() {
        return new ArrayList<>(PAGE_RESULTS);
    }

    /** Builds a page result from counters captured around the page's work. */
    public static PageApiResult buildPageResult(String pageName, int failuresBefore, int responsesBefore,
                                                List<String> pendingApis) {
        List<ApiFailure> all = getFailures();
        List<ApiFailure> mine = all.size() > failuresBefore
                ? new ArrayList<>(all.subList(failuresBefore, all.size()))
                : new ArrayList<>();

        List<String> failedApis = new ArrayList<>();
        for (ApiFailure failure : mine) {
            failedApis.add(failure.method() + " " + shortUrl(failure.url()) + " -> " + failure.status()
                    + (failure.error() == null ? "" : " (" + failure.error() + ")"));
        }

        int responses = Math.max(0, TOTAL_RESPONSES.get() - responsesBefore);
        int failed = failedApis.size();
        int timeouts = pendingApis.size();
        int requests = responses + timeouts;
        int successful = Math.max(0, responses - failed);

        return new PageApiResult(pageName, currentIteration, requests, responses, successful,
                failed, timeouts, failedApis, pendingApis);
    }

    private static String shortUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            return path == null || path.isBlank() ? url : path;
        } catch (Exception exception) {
            return url;
        }
    }

    // ---------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------

    /** Writes the consolidated report, swallowing any error. Intended for teardown paths. */
    public static void writeReportQuietly() {
        try {
            writeReport();
        } catch (Exception exception) {
            System.err.println("[API MONITOR] Could not write the API failure report: " + exception.getMessage());
        }
    }

    /**
     * Writes every failure captured so far to the consolidated log file, replacing any previous
     * content so repeated calls stay idempotent.
     *
     * @return the path written, or {@code null} when monitoring is disabled
     */
    public static Path writeReport() throws IOException {
        if (!isEnabled()) {
            return null;
        }
        Path logFile = getLogFile();
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, buildReport(), StandardCharsets.UTF_8);
        System.out.println("[API MONITOR] " + FAILURES.size() + " distinct API failure(s) written to " + logFile);
        return logFile;
    }

    /**
     * Writes the same consolidated report into an additional directory, so a per-execution evidence
     * folder can carry its own copy alongside {@code result.json}. Never throws.
     *
     * @return the path written, or {@code null} when monitoring is disabled or the write failed
     */
    public static Path writeReportToQuietly(Path directory) {
        try {
            if (!isEnabled() || directory == null) {
                return null;
            }
            Files.createDirectories(directory);
            Path logFile = directory.resolve(LOG_FILE_NAME);
            Files.writeString(logFile, buildReport(), StandardCharsets.UTF_8);
            System.out.println("[API MONITOR] Evidence copy written to " + logFile);
            return logFile;
        } catch (Exception exception) {
            System.err.println("[API MONITOR] Could not write the evidence copy: " + exception.getMessage());
            return null;
        }
    }

    /** Renders the report body. Exposed for tests and ad-hoc reporting. */
    public static String buildReport() {
        StringBuilder report = new StringBuilder();
        List<ApiFailure> snapshot = getFailures();

        if (snapshot.isEmpty()) {
            report.append(SEPARATOR).append(System.lineSeparator())
                    .append("API MONITORING RESULT").append(System.lineSeparator())
                    .append(SEPARATOR).append(System.lineSeparator())
                    .append("No failed API responses detected.").append(System.lineSeparator())
                    .append("Expected statuses: 200, 201, 204").append(System.lineSeparator())
                    .append(SEPARATOR).append(System.lineSeparator())
                    .append(System.lineSeparator());
        } else {
            int index = 1;
            for (ApiFailure failure : snapshot) {
                report.append(SEPARATOR).append(System.lineSeparator())
                        .append("API FAILURE #").append(index++).append(System.lineSeparator())
                        .append(SEPARATOR).append(System.lineSeparator())
                        .append(System.lineSeparator())
                        .append("Timestamp       : ").append(failure.timestamp()).append(System.lineSeparator())
                        .append("Soak Iteration  : ").append(failure.iteration()).append(System.lineSeparator())
                        .append("Page            : ").append(failure.pageName()).append(System.lineSeparator())
                        .append("Operation       : ").append(failure.operation()).append(System.lineSeparator())
                        .append("HTTP Method     : ").append(failure.method()).append(System.lineSeparator())
                        .append("Status Code     : ").append(failure.status()).append(System.lineSeparator())
                        .append("Status Text     : ").append(failure.statusText()).append(System.lineSeparator())
                        .append("Request URL     : ").append(failure.url()).append(System.lineSeparator())
                        .append("Resource Type   : ").append(failure.resourceType()).append(System.lineSeparator());
                if (failure.occurrences() > 1) {
                    report.append("Occurrences     : ").append(failure.occurrences()).append(System.lineSeparator());
                }
                report.append("Response Body   : ").append(failure.responseBody()).append(System.lineSeparator())
                        .append("Error           : ").append(failure.error() == null ? "-" : failure.error())
                        .append(System.lineSeparator())
                        .append(SEPARATOR).append(System.lineSeparator())
                        .append(System.lineSeparator());
            }
        }

        report.append(buildSummary(snapshot));
        return report.toString();
    }

    private static String buildSummary(List<ApiFailure> snapshot) {
        Map<Integer, Integer> failureCounts = new LinkedHashMap<>();
        for (int status : SUMMARY_STATUSES) {
            failureCounts.put(status, 0);
        }

        int totalFailures = 0;
        Set<Integer> extraStatuses = new TreeSet<>();
        Set<String> failedPages = new TreeSet<>();
        for (ApiFailure failure : snapshot) {
            int occurrences = failure.occurrences();
            totalFailures += occurrences;
            failureCounts.merge(failure.status(), occurrences, Integer::sum);
            failedPages.add(failure.pageName());
            boolean listed = false;
            for (int status : SUMMARY_STATUSES) {
                if (status == failure.status()) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                extraStatuses.add(failure.status());
            }
        }

        List<PageApiResult> pages = getPageResults();
        int pagesPassed = 0;
        for (PageApiResult page : pages) {
            if (page.isPassed()) {
                pagesPassed++;
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append(SEPARATOR).append(System.lineSeparator())
                .append("SOAK TEST API SUMMARY").append(System.lineSeparator())
                .append(SEPARATOR).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Total Pages        : ").append(pages.size()).append(System.lineSeparator())
                .append("Pages Passed       : ").append(pagesPassed).append(System.lineSeparator())
                .append("Pages Failed       : ").append(pages.size() - pagesPassed).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Total API Requests : ").append(TOTAL_RESPONSES.get() + TIMEOUTS.get())
                .append(System.lineSeparator())
                .append("Successful         : ").append(Math.max(0, TOTAL_RESPONSES.get() - totalFailures))
                .append(System.lineSeparator())
                .append("Failed             : ").append(totalFailures).append(System.lineSeparator())
                .append("Timeouts           : ").append(TIMEOUTS.get()).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(SUB_SEPARATOR).append(System.lineSeparator())
                .append("STATUS SUMMARY").append(System.lineSeparator())
                .append(SUB_SEPARATOR).append(System.lineSeparator())
                .append(System.lineSeparator());

        for (int status : new int[] {200, 201, 204}) {
            summary.append(formatCount(status, statusCount(status)));
        }
        for (int status : SUMMARY_STATUSES) {
            summary.append(formatCount(status, Math.max(statusCount(status), failureCounts.get(status))));
        }
        for (int status : extraStatuses) {
            summary.append(formatCount(status, failureCounts.getOrDefault(status, statusCount(status))));
        }

        int dropped = DROPPED.get();
        if (dropped > 0) {
            summary.append("Not recorded       : ").append(dropped)
                    .append(" (cap of ").append(MAX_STORED_FAILURES).append(" distinct failures reached)")
                    .append(System.lineSeparator());
        }

        if (!pages.isEmpty()) {
            summary.append(System.lineSeparator())
                    .append(SUB_SEPARATOR).append(System.lineSeparator())
                    .append("PAGE RESULTS").append(System.lineSeparator())
                    .append(SUB_SEPARATOR).append(System.lineSeparator())
                    .append(System.lineSeparator());
            for (PageApiResult page : pages) {
                summary.append(String.format("%-34s iteration %-3d %-4s  requests=%-4d failed=%-3d timeouts=%d%s",
                        page.pageName(), page.iteration(), page.isPassed() ? "PASS" : "FAIL",
                        page.requests(), page.failed(), page.timeouts(), System.lineSeparator()));
            }
        }

        if (!failedPages.isEmpty()) {
            summary.append(System.lineSeparator())
                    .append(SUB_SEPARATOR).append(System.lineSeparator())
                    .append("FAILED PAGES").append(System.lineSeparator())
                    .append(SUB_SEPARATOR).append(System.lineSeparator())
                    .append(System.lineSeparator());
            for (String page : failedPages) {
                summary.append(page).append(System.lineSeparator());
            }
        }

        if (!snapshot.isEmpty()) {
            summary.append(System.lineSeparator())
                    .append(SUB_SEPARATOR).append(System.lineSeparator())
                    .append("FAILED APIs").append(System.lineSeparator())
                    .append(SUB_SEPARATOR).append(System.lineSeparator())
                    .append(System.lineSeparator());
            for (ApiFailure failure : snapshot) {
                summary.append(String.format("%-6s %-60s -> %d%s", failure.method(),
                        shortUrl(failure.url()), failure.status(), System.lineSeparator()));
            }
        }

        summary.append(SEPARATOR).append(System.lineSeparator());
        return summary.toString();
    }

    private static int statusCount(int status) {
        AtomicInteger count = STATUS_COUNTS.get(status);
        return count == null ? 0 : count.get();
    }

    /** Renders one summary row, aligned with the "Total API failures : " label above it. */
    private static String formatCount(int status, int count) {
        return String.format("%-19s: %d%s", status, count, System.lineSeparator());
    }

    // ---------------------------------------------------------------------
    // API inventory (opt-in, separate from the failure report above)
    // ---------------------------------------------------------------------

    /** Snapshot of the distinct API inventory entries captured so far, in first-seen order. */
    public static List<ApiInventoryEntry> getInventory() {
        return new ArrayList<>(INVENTORY);
    }

    /** Number of distinct calls recorded in the inventory (0 whenever the feature is disabled). */
    public static int getInventoryCount() {
        return INVENTORY.size();
    }

    /** Destination of the API inventory JSON file. */
    public static Path getInventoryLogFile() {
        String directory = ConfigReader.getOrDefault(
                "api.inventory.output.directory", DEFAULT_INVENTORY_OUTPUT_DIRECTORY);
        if (directory.isBlank()) {
            directory = DEFAULT_INVENTORY_OUTPUT_DIRECTORY;
        }
        return Paths.get(directory, INVENTORY_FILE_NAME).toAbsolutePath();
    }

    /**
     * Writes the API inventory to its JSON file. A no-op returning {@code null} whenever
     * {@code api.inventory.enabled} is not {@code true} - the default - so nothing changes for
     * anyone who has not opted in.
     */
    public static Path writeInventoryReport() throws IOException {
        if (!isInventoryEnabled()) {
            return null;
        }
        Path file = getInventoryLogFile();
        Files.createDirectories(file.getParent());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ApiInventoryEntry entry : getInventory()) {
            entries.add(entry.toJsonMap());
        }
        INVENTORY_JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        System.out.println("[API MONITOR] " + entries.size() + " distinct API inventory entr"
                + (entries.size() == 1 ? "y" : "ies") + " written to " + file);
        return file;
    }

    /** Writes the API inventory, swallowing any error. Intended for teardown paths. */
    public static void writeInventoryReportQuietly() {
        try {
            writeInventoryReport();
        } catch (Exception exception) {
            System.err.println("[API MONITOR] Could not write the API inventory report: " + exception.getMessage());
        }
    }

    /** Off by default: the API inventory only ever grows when this is explicitly turned on. */
    private static boolean isInventoryEnabled() {
        try {
            return Boolean.parseBoolean(ConfigReader.getOrDefault("api.inventory.enabled", "false"));
        } catch (Exception exception) {
            return false;
        }
    }

    private static int maxInventoryEntries() {
        try {
            String value = ConfigReader.getOrDefault("api.inventory.max.entries", String.valueOf(MAX_INVENTORY_ENTRIES));
            return value.isBlank() ? MAX_INVENTORY_ENTRIES : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return MAX_INVENTORY_ENTRIES;
        }
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    /** Snapshot of the distinct failures captured so far, in the order they first occurred. */
    public static List<ApiFailure> getFailures() {
        return new ArrayList<>(FAILURES);
    }

    /** Number of distinct failing endpoints captured. */
    public static int getDistinctFailureCount() {
        return FAILURES.size();
    }

    /** Total failing responses observed, counting repeats of the same endpoint. */
    public static int getTotalFailureCount() {
        int total = 0;
        for (ApiFailure failure : FAILURES) {
            total += failure.occurrences();
        }
        return total;
    }

    /** Clears captured state so a fresh execution starts from zero. */
    public static void reset() {
        FAILURES.clear();
        FAILURES_BY_KEY.clear();
        DROPPED.set(0);
        IN_FLIGHT.clear();
        STATUS_COUNTS.clear();
        TOTAL_RESPONSES.set(0);
        TIMEOUTS.set(0);
        PAGE_RESULTS.clear();
        INVENTORY.clear();
        INVENTORY_BY_KEY.clear();
        INVENTORY_DROPPED.set(0);
    }

    /** Destination of the consolidated report. */
    public static Path getLogFile() {
        String directory = ConfigReader.getOrDefault("api.monitor.output.directory", DEFAULT_OUTPUT_DIRECTORY);
        if (directory.isBlank()) {
            directory = DEFAULT_OUTPUT_DIRECTORY;
        }
        return Paths.get(directory, LOG_FILE_NAME).toAbsolutePath();
    }

    /** When true, navigation-cancelled requests (net::ERR_ABORTED) are recorded too. Off by default. */
    private static boolean recordAbortedRequests() {
        try {
            return Boolean.parseBoolean(ConfigReader.getOrDefault("api.monitor.record.aborted", "false"));
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isEnabled() {
        try {
            return Boolean.parseBoolean(ConfigReader.getOrDefault("api.monitor.enabled", "true"));
        } catch (Exception exception) {
            return true;
        }
    }
}
