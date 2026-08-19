package com.vigilx.reporting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.monitoring.ApiMonitor;

/**
 * Failure-centric collector and report generator for a soak execution.
 *
 * <p>Records only failures - page, stream and (via {@link ApiMonitor}) API. Successful validations
 * are counted for the summary but never written as individual records, so the report stays small
 * and readable no matter how long the soak runs.
 *
 * <p>Every method swallows its own errors: reporting must never turn into a test failure.
 */
public final class SoakReporter {

    private static final String SEPARATOR = "============================================================";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ConcurrentLinkedQueue<PageFailure> PAGE_FAILURES = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<StreamFailure> STREAM_FAILURES = new ConcurrentLinkedQueue<>();

    /** Dedup index for repeated identical stream failures; the counter preserves the repeat count. */
    private static final ConcurrentHashMap<String, StreamFailure> STREAM_INDEX = new ConcurrentHashMap<>();

    /** Screenshot recorded per page+iteration, so an API failure can be cross-referenced to the UI. */
    private static final ConcurrentHashMap<String, String> SCREENSHOTS = new ConcurrentHashMap<>();

    /** Distinct stream failures already screenshotted, to avoid one image per monitoring pass. */
    private static final java.util.Set<String> SCREENSHOT_TAKEN = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger PAGES_PASSED = new AtomicInteger();

    private SoakReporter() {
    }

    /** A page that failed, with the reason and any screenshot taken. */
    public static final class PageFailure {
        private final String timestamp;
        private final int iteration;
        private final String page;
        private final String failureType;
        private final int failedApis;
        private final String screenshot;
        private final String reason;

        PageFailure(String timestamp, int iteration, String page, String failureType,
                    int failedApis, String screenshot, String reason) {
            this.timestamp = timestamp;
            this.iteration = iteration;
            this.page = page;
            this.failureType = failureType;
            this.failedApis = failedApis;
            this.screenshot = screenshot;
            this.reason = reason;
        }

        public String timestamp() { return timestamp; }
        public int iteration() { return iteration; }
        public String page() { return page; }
        public String failureType() { return failureType; }
        public int failedApis() { return failedApis; }
        public String screenshot() { return screenshot; }
        public String reason() { return reason; }
    }

    /** A camera/video stream that failed, with the media state at the time. */
    public static final class StreamFailure {
        private final String timestamp;
        private final int iteration;
        private final String page;
        private final String device;
        private final String videoElement;
        private final String videoVisible;
        private final String videoSource;
        private final int readyState;
        private final String resolution;
        private final boolean paused;
        private final double currentTime;
        private final String failure;
        private final String screenshot;
        private final AtomicInteger occurrences = new AtomicInteger(1);
        private volatile int lastIteration;

        StreamFailure(String timestamp, int iteration, String page, String device, String videoElement,
                      String videoVisible, String videoSource, int readyState, String resolution,
                      boolean paused, double currentTime, String failure, String screenshot) {
            this.timestamp = timestamp;
            this.iteration = iteration;
            this.page = page;
            this.device = device;
            this.videoElement = videoElement;
            this.videoVisible = videoVisible;
            this.videoSource = videoSource;
            this.readyState = readyState;
            this.resolution = resolution;
            this.paused = paused;
            this.currentTime = currentTime;
            this.failure = failure;
            this.screenshot = screenshot;
            this.lastIteration = iteration;
        }

        public int lastIteration() { return lastIteration; }

        public String timestamp() { return timestamp; }
        public int iteration() { return iteration; }
        public String page() { return page; }
        public String device() { return device; }
        public String failure() { return failure; }
        public String screenshot() { return screenshot; }
        public int occurrences() { return occurrences.get(); }
    }

    // ---------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------

    /** Counts a page that passed. No record is written; it only feeds the summary counts. */
    public static void recordPagePassed() {
        PAGES_PASSED.incrementAndGet();
    }

    /** Records a failed page. Never throws. */
    public static void recordPageFailure(String page, String failureType, int failedApis,
                                         String screenshot, String reason) {
        try {
            PAGE_FAILURES.add(new PageFailure(now(), ApiMonitor.getIteration(), page, failureType,
                    failedApis, SoakRunContext.relative(screenshot), reason));
        } catch (Exception exception) {
            System.err.println("[SOAK REPORT] Could not record page failure: " + exception.getMessage());
        }
    }

    /**
     * True the first time a given page+device+failure is seen in this run. Callers use it to take
     * one screenshot per distinct failure instead of one per monitoring pass - a 60s Live View loop
     * over 5 cameras would otherwise write 25 near-identical images.
     */
    public static boolean shouldCaptureScreenshot(String page, String device, String failure) {
        try {
            return SCREENSHOT_TAKEN.add(page + "|" + device + "|" + failure);
        } catch (Exception exception) {
            return true;
        }
    }

    /**
     * Records a failed stream. Repeats of the same failure on the same device collapse into one
     * record that counts occurrences and tracks the first/last iteration, so a long soak shows
     * "failed 25 times across iterations 1-5" rather than 25 near-identical entries.
     */
    public static void recordStreamFailure(String page, String device, String videoElement,
                                           String videoVisible, String videoSource, int readyState,
                                           String resolution, boolean paused, double currentTime,
                                           String failure, String screenshot) {
        try {
            int iteration = ApiMonitor.getIteration();
            String key = page + "|" + device + "|" + failure;
            StreamFailure known = STREAM_INDEX.get(key);
            if (known != null) {
                known.occurrences.incrementAndGet();
                known.lastIteration = iteration;
                return;
            }
            StreamFailure record = new StreamFailure(now(), iteration, page, device, videoElement,
                    videoVisible, videoSource, readyState, resolution, paused, currentTime, failure,
                    SoakRunContext.relative(screenshot));
            StreamFailure raced = STREAM_INDEX.putIfAbsent(key, record);
            if (raced == null) {
                STREAM_FAILURES.add(record);
            } else {
                raced.occurrences.incrementAndGet();
                raced.lastIteration = iteration;
            }
        } catch (Exception exception) {
            System.err.println("[SOAK REPORT] Could not record stream failure: " + exception.getMessage());
        }
    }

    /** Associates a failure screenshot with a page so API failures can cross-reference it. */
    public static void registerScreenshot(String page, String screenshotPath) {
        if (page == null || screenshotPath == null) {
            return;
        }
        SCREENSHOTS.putIfAbsent(ApiMonitor.getIteration() + "|" + page, SoakRunContext.relative(screenshotPath));
    }

    private static String screenshotFor(String page, int iteration) {
        return SCREENSHOTS.get(iteration + "|" + page);
    }

    private static String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    // ---------------------------------------------------------------------
    // Report generation
    // ---------------------------------------------------------------------

    /**
     * Writes every report file for this run. Never throws - a reporting problem is logged and the
     * automation continues.
     */
    public static void writeAll(String overallResult, int iterations) {
        SoakRunContext run = SoakRunContext.current();
        try {
            writePageFailures(run);
            writeStreamFailures(run);
            writeApiFailureJson(run);
            ApiMonitor.writeReportToQuietly(run.apiFailureDirectory());
            writeFinalReports(run, overallResult, iterations);
            System.out.println("[SOAK REPORT] Final report: "
                    + run.finalReportDirectory().resolve("soak-test-report.html").toAbsolutePath());
        } catch (Exception exception) {
            System.err.println("[SOAK REPORT] Report generation failed: " + exception.getMessage());
        }
    }

    private static void writePageFailures(SoakRunContext run) {
        StringBuilder text = new StringBuilder();
        List<PageFailure> failures = new ArrayList<>(PAGE_FAILURES);
        if (failures.isEmpty()) {
            text.append("No page failures detected.").append(System.lineSeparator());
        } else {
            int index = 1;
            for (PageFailure failure : failures) {
                text.append(SEPARATOR).append(System.lineSeparator())
                        .append(String.format("PAGE FAILURE #%03d", index++)).append(System.lineSeparator())
                        .append(SEPARATOR).append(System.lineSeparator())
                        .append("Timestamp     : ").append(failure.timestamp()).append(System.lineSeparator())
                        .append("Iteration     : ").append(failure.iteration()).append(System.lineSeparator())
                        .append("Page          : ").append(failure.page()).append(System.lineSeparator())
                        .append("Failure Type  : ").append(failure.failureType()).append(System.lineSeparator())
                        .append("Failed APIs   : ").append(failure.failedApis()).append(System.lineSeparator())
                        .append("Screenshot    : ").append(value(failure.screenshot())).append(System.lineSeparator())
                        .append("Reason        : ").append(value(failure.reason())).append(System.lineSeparator())
                        .append(SEPARATOR).append(System.lineSeparator())
                        .append(System.lineSeparator());
            }
        }
        write(run.pageFailureDirectory().resolve("page-failures.log"), text.toString());
    }

    private static void writeStreamFailures(SoakRunContext run) {
        StringBuilder text = new StringBuilder();
        List<StreamFailure> failures = new ArrayList<>(STREAM_FAILURES);
        if (failures.isEmpty()) {
            text.append("No stream failures detected.").append(System.lineSeparator());
        } else {
            int index = 1;
            for (StreamFailure failure : failures) {
                text.append(SEPARATOR).append(System.lineSeparator())
                        .append(String.format("STREAM FAILURE #%03d", index++)).append(System.lineSeparator())
                        .append(SEPARATOR).append(System.lineSeparator())
                        .append("Timestamp      : ").append(failure.timestamp).append(System.lineSeparator())
                        .append("Iteration      : ").append(failure.iteration).append(System.lineSeparator())
                        .append("Page           : ").append(failure.page).append(System.lineSeparator())
                        .append("Device         : ").append(failure.device).append(System.lineSeparator())
                        .append("Video Element  : ").append(failure.videoElement).append(System.lineSeparator())
                        .append("Video Visible  : ").append(failure.videoVisible).append(System.lineSeparator())
                        .append("Video Source   : ").append(failure.videoSource).append(System.lineSeparator())
                        .append("Ready State    : ").append(failure.readyState).append(System.lineSeparator())
                        .append("Resolution     : ").append(failure.resolution).append(System.lineSeparator())
                        .append("Paused         : ").append(failure.paused ? "YES" : "NO").append(System.lineSeparator())
                        .append("Current Time   : ").append(failure.currentTime).append(System.lineSeparator())
                        .append("Failure        : ").append(failure.failure).append(System.lineSeparator());
                if (failure.occurrences() > 1) {
                    text.append("Occurrences    : ").append(failure.occurrences())
                            .append(failure.lastIteration() > failure.iteration
                                    ? "  (repeated, iterations " + failure.iteration + "-"
                                            + failure.lastIteration() + ")"
                                    : "  (repeated within iteration " + failure.iteration + ")")
                            .append(System.lineSeparator());
                }
                text.append("Screenshot     : ").append(value(failure.screenshot)).append(System.lineSeparator())
                        .append(SEPARATOR).append(System.lineSeparator())
                        .append(System.lineSeparator());
            }
        }
        write(run.streamFailureDirectory().resolve("stream-failures.log"), text.toString());
    }

    /** Structured record of failed APIs only. */
    private static void writeApiFailureJson(SoakRunContext run) {
        try {
            List<Map<String, Object>> records = new ArrayList<>();
            for (ApiMonitor.ApiFailure failure : ApiMonitor.getFailures()) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("timestamp", failure.timestamp());
                record.put("iteration", failure.iteration());
                record.put("page", failure.pageName());
                record.put("operation", failure.operation());
                record.put("method", failure.method());
                record.put("status", failure.status());
                record.put("statusText", failure.statusText());
                record.put("url", failure.url());
                record.put("resourceType", failure.resourceType());
                record.put("responseBody", failure.responseBody());
                record.put("occurrences", failure.occurrences());
                record.put("error", failure.error());
                record.put("screenshot", screenshotFor(failure.pageName(), failure.iteration()));
                records.add(record);
            }
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(run.apiFailureDirectory().resolve("api-failures.json").toFile(), records);
        } catch (Exception exception) {
            System.err.println("[SOAK REPORT] Could not write api-failures.json: " + exception.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Final report
    // ---------------------------------------------------------------------

    private static void writeFinalReports(SoakRunContext run, String overallResult, int iterations) {
        List<ApiMonitor.ApiFailure> apiFailures = ApiMonitor.getFailures();
        List<ApiMonitor.PageApiResult> pageResults = ApiMonitor.getPageResults();
        List<PageFailure> pageFailures = new ArrayList<>(PAGE_FAILURES);
        List<StreamFailure> streamFailures = new ArrayList<>(STREAM_FAILURES);
        List<String[]> timeline = buildTimeline(apiFailures, pageFailures, streamFailures);

        int totalFailedApis = 0;
        Map<Integer, Integer> statusDistribution = new LinkedHashMap<>();
        for (ApiMonitor.ApiFailure failure : apiFailures) {
            totalFailedApis += failure.occurrences();
            statusDistribution.merge(failure.status(), failure.occurrences(), Integer::sum);
        }
        int timeouts = 0;
        for (ApiMonitor.PageApiResult page : pageResults) {
            timeouts += page.timeouts();
        }
        int totalResponses = ApiMonitor.getTotalResponses();
        int successfulApis = Math.max(0, totalResponses - totalFailedApis);

        LocalDateTime start = run.startedAt();
        LocalDateTime end = LocalDateTime.now();
        String duration = formatDuration(Duration.between(start, end));

        writeTextReport(run, overallResult, iterations, start, end, duration, pageResults, pageFailures,
                apiFailures, streamFailures, timeline, totalResponses, successfulApis, totalFailedApis,
                timeouts, statusDistribution);

        writeHtmlReport(run, overallResult, iterations, start, end, duration, pageResults, pageFailures,
                apiFailures, streamFailures, timeline, totalResponses, successfulApis, totalFailedApis,
                timeouts, statusDistribution);

        writeJsonSummary(run, overallResult, iterations, start, end, duration, pageResults, pageFailures,
                apiFailures, streamFailures, totalResponses, successfulApis, totalFailedApis, timeouts,
                statusDistribution);
    }

    /** All failures merged and ordered by time, for debugging the run. */
    private static List<String[]> buildTimeline(List<ApiMonitor.ApiFailure> apiFailures,
                                                List<PageFailure> pageFailures,
                                                List<StreamFailure> streamFailures) {
        List<String[]> timeline = new ArrayList<>();
        for (ApiMonitor.ApiFailure failure : apiFailures) {
            timeline.add(new String[] {failure.timestamp(), "API",
                    failure.pageName() + " " + failure.method() + " " + shortUrl(failure.url())
                            + " -> " + failure.status()});
        }
        for (PageFailure failure : pageFailures) {
            timeline.add(new String[] {failure.timestamp(), "PAGE",
                    failure.page() + " -> " + failure.failureType()});
        }
        for (StreamFailure failure : streamFailures) {
            timeline.add(new String[] {failure.timestamp, "STREAM",
                    failure.page + " " + failure.device + " -> " + failure.failure});
        }
        timeline.sort(Comparator.comparing(entry -> entry[0]));
        return timeline;
    }

    private static void writeTextReport(SoakRunContext run, String overallResult, int iterations,
                                        LocalDateTime start, LocalDateTime end, String duration,
                                        List<ApiMonitor.PageApiResult> pageResults,
                                        List<PageFailure> pageFailures,
                                        List<ApiMonitor.ApiFailure> apiFailures,
                                        List<StreamFailure> streamFailures,
                                        List<String[]> timeline, int totalResponses, int successfulApis,
                                        int failedApis, int timeouts, Map<Integer, Integer> statuses) {

        StringBuilder text = new StringBuilder();
        text.append(SEPARATOR).append(System.lineSeparator())
                .append("SOAK TEST SUMMARY").append(System.lineSeparator())
                .append(SEPARATOR).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Start Time        : ").append(start.format(TIMESTAMP)).append(System.lineSeparator())
                .append("End Time          : ").append(end.format(TIMESTAMP)).append(System.lineSeparator())
                .append("Duration          : ").append(duration).append(System.lineSeparator())
                .append("Total Iterations  : ").append(iterations).append(System.lineSeparator())
                .append("Evidence Folder   : ").append(SoakRunContext.relative(run.runDirectory().toString()))
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(SEPARATOR).append(System.lineSeparator())
                .append("OVERALL RESULT: ").append(overallResult).append(System.lineSeparator())
                .append(SEPARATOR).append(System.lineSeparator())
                .append(System.lineSeparator());

        if (apiFailures.isEmpty() && pageFailures.isEmpty() && streamFailures.isEmpty()) {
            text.append("No API failures detected.").append(System.lineSeparator())
                    .append("No page failures detected.").append(System.lineSeparator())
                    .append("No stream failures detected.").append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        int pagesFailed = pageFailures.size();
        int pagesTested = Math.max(pageResults.size(), pagesFailed + PAGES_PASSED.get());
        text.append("PAGE SUMMARY").append(System.lineSeparator())
                .append("Total Pages Tested : ").append(pagesTested).append(System.lineSeparator())
                .append("Passed             : ").append(Math.max(0, pagesTested - pagesFailed))
                .append(System.lineSeparator())
                .append("Failed             : ").append(pagesFailed).append(System.lineSeparator())
                .append(System.lineSeparator());
        if (!pageFailures.isEmpty()) {
            text.append("Failed Pages:").append(System.lineSeparator());
            for (PageFailure failure : pageFailures) {
                text.append("  - ").append(failure.page()).append(" (iteration ")
                        .append(failure.iteration()).append(") ").append(failure.failureType())
                        .append(System.lineSeparator());
            }
            text.append(System.lineSeparator());
        }

        text.append("API SUMMARY").append(System.lineSeparator())
                .append("Total API Requests : ").append(totalResponses + timeouts).append(System.lineSeparator())
                .append("Successful         : ").append(successfulApis).append(System.lineSeparator())
                .append("Failed             : ").append(failedApis).append(System.lineSeparator())
                .append("Timeouts           : ").append(timeouts).append(System.lineSeparator())
                .append(System.lineSeparator());
        if (!statuses.isEmpty()) {
            text.append("Failed API Status Distribution:").append(System.lineSeparator());
            for (Map.Entry<Integer, Integer> entry : statuses.entrySet()) {
                text.append(String.format("  %-5s : %d%s", entry.getKey(), entry.getValue(),
                        System.lineSeparator()));
            }
            text.append(System.lineSeparator());
        }

        if (!apiFailures.isEmpty()) {
            text.append(SEPARATOR).append(System.lineSeparator())
                    .append("FAILED APIs").append(System.lineSeparator())
                    .append(SEPARATOR).append(System.lineSeparator()).append(System.lineSeparator());
            for (ApiMonitor.ApiFailure failure : apiFailures) {
                text.append("Page       : ").append(failure.pageName()).append(System.lineSeparator())
                        .append("Iteration  : ").append(failure.iteration()).append(System.lineSeparator())
                        .append("Timestamp  : ").append(failure.timestamp()).append(System.lineSeparator())
                        .append("Method     : ").append(failure.method()).append(System.lineSeparator())
                        .append("Status     : ").append(failure.status()).append(" ")
                        .append(failure.statusText()).append(System.lineSeparator())
                        .append("URL        : ").append(failure.url()).append(System.lineSeparator())
                        .append("Operation  : ").append(failure.operation()).append(System.lineSeparator())
                        .append("Response   : ").append(trim(failure.responseBody())).append(System.lineSeparator())
                        .append("Screenshot : ")
                        .append(value(screenshotFor(failure.pageName(), failure.iteration())))
                        .append(System.lineSeparator())
                        .append("------------------------------------------------------------")
                        .append(System.lineSeparator());
            }
            text.append(System.lineSeparator());
        }

        if (!streamFailures.isEmpty()) {
            text.append(SEPARATOR).append(System.lineSeparator())
                    .append("STREAM FAILURES").append(System.lineSeparator())
                    .append(SEPARATOR).append(System.lineSeparator()).append(System.lineSeparator());
            for (StreamFailure failure : streamFailures) {
                text.append("Page       : ").append(failure.page).append(System.lineSeparator())
                        .append("Iteration  : ").append(failure.iteration).append(System.lineSeparator())
                        .append("Device     : ").append(failure.device).append(System.lineSeparator())
                        .append("Failure    : ").append(failure.failure).append(System.lineSeparator())
                        .append("Resolution : ").append(failure.resolution).append(System.lineSeparator())
                        .append("ReadyState : ").append(failure.readyState).append(System.lineSeparator())
                        .append("CurrentTime: ").append(failure.currentTime).append(System.lineSeparator())
                        .append("Occurrences: ").append(failure.occurrences()).append(System.lineSeparator())
                        .append("Screenshot : ").append(value(failure.screenshot)).append(System.lineSeparator())
                        .append("------------------------------------------------------------")
                        .append(System.lineSeparator());
            }
            text.append(System.lineSeparator());
        }

        if (!timeline.isEmpty()) {
            text.append(SEPARATOR).append(System.lineSeparator())
                    .append("FAILURE TIMELINE").append(System.lineSeparator())
                    .append(SEPARATOR).append(System.lineSeparator()).append(System.lineSeparator());
            for (String[] entry : timeline) {
                text.append(entry[0]).append("  [").append(entry[1]).append("]  ")
                        .append(entry[2]).append(System.lineSeparator());
            }
            text.append(System.lineSeparator());
        }

        text.append(SEPARATOR).append(System.lineSeparator());
        write(run.finalReportDirectory().resolve("soak-test-report.txt"), text.toString());
    }

    private static void writeHtmlReport(SoakRunContext run, String overallResult, int iterations,
                                        LocalDateTime start, LocalDateTime end, String duration,
                                        List<ApiMonitor.PageApiResult> pageResults,
                                        List<PageFailure> pageFailures,
                                        List<ApiMonitor.ApiFailure> apiFailures,
                                        List<StreamFailure> streamFailures,
                                        List<String[]> timeline, int totalResponses, int successfulApis,
                                        int failedApis, int timeouts, Map<Integer, Integer> statuses) {

        boolean passed = "PASS".equalsIgnoreCase(overallResult);
        int pagesFailed = pageFailures.size();
        int pagesTested = Math.max(pageResults.size(), pagesFailed + PAGES_PASSED.get());

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Soak Test Report</title><style>")
                .append(":root{--bg:#f6f7f9;--card:#fff;--text:#1c1f23;--muted:#606a76;--line:#e3e6ea;")
                .append("--pass:#1a7f4b;--fail:#c0392b;--warn:#b8860b}")
                .append("@media(prefers-color-scheme:dark){:root{--bg:#15181c;--card:#1d2126;--text:#e8eaed;")
                .append("--muted:#9aa4b0;--line:#2c3238;--pass:#4ade80;--fail:#f87171;--warn:#fbbf24}}")
                .append("*{box-sizing:border-box}body{margin:0;padding:32px 20px;background:var(--bg);")
                .append("color:var(--text);font:15px/1.55 -apple-system,Segoe UI,Roboto,sans-serif}")
                .append(".wrap{max-width:1100px;margin:0 auto}")
                .append("h1{font-size:24px;margin:0 0 4px}h2{font-size:17px;margin:32px 0 12px;")
                .append("padding-bottom:8px;border-bottom:1px solid var(--line)}")
                .append(".sub{color:var(--muted);margin:0 0 24px;font-size:13px}")
                .append(".verdict{display:inline-block;padding:6px 16px;border-radius:6px;font-weight:700;")
                .append("letter-spacing:.5px;color:#fff}.verdict.pass{background:var(--pass)}")
                .append(".verdict.fail{background:var(--fail)}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}")
                .append(".card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:14px}")
                .append(".card .n{font-size:22px;font-weight:700}.card .l{color:var(--muted);font-size:12px;")
                .append("text-transform:uppercase;letter-spacing:.4px}")
                .append(".n.fail{color:var(--fail)}.n.pass{color:var(--pass)}.n.warn{color:var(--warn)}")
                .append("table{width:100%;border-collapse:collapse;background:var(--card);")
                .append("border:1px solid var(--line);border-radius:8px;overflow:hidden;font-size:13px}")
                .append("th,td{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);")
                .append("vertical-align:top}th{background:rgba(128,128,128,.08);font-size:12px;")
                .append("text-transform:uppercase;letter-spacing:.4px;color:var(--muted)}")
                .append("tr:last-child td{border-bottom:none}code{font:12px ui-monospace,Menlo,Consolas,monospace;")
                .append("word-break:break-all}.tw{overflow-x:auto}")
                .append(".s{font-weight:700}.s.e{color:var(--fail)}.ok{color:var(--pass);font-weight:600}")
                .append("</style></head><body><div class=\"wrap\">");

        html.append("<h1>Soak Test Report</h1>")
                .append("<p class=\"sub\">").append(escape(start.format(TIMESTAMP))).append(" &rarr; ")
                .append(escape(end.format(TIMESTAMP))).append(" &middot; ").append(escape(duration))
                .append(" &middot; ").append(iterations).append(" iteration(s) &middot; ")
                .append(escape(SoakRunContext.relative(run.runDirectory().toString()))).append("</p>")
                .append("<p><span class=\"verdict ").append(passed ? "pass" : "fail").append("\">")
                .append(escape(overallResult)).append("</span></p>");

        html.append("<div class=\"grid\">")
                .append(card("Pages tested", String.valueOf(pagesTested), ""))
                .append(card("Pages failed", String.valueOf(pagesFailed), pagesFailed > 0 ? "fail" : "pass"))
                .append(card("API requests", String.valueOf(totalResponses + timeouts), ""))
                .append(card("API successful", String.valueOf(successfulApis), "pass"))
                .append(card("API failed", String.valueOf(failedApis), failedApis > 0 ? "fail" : "pass"))
                .append(card("API timeouts", String.valueOf(timeouts), timeouts > 0 ? "warn" : "pass"))
                .append(card("Stream failures", String.valueOf(streamFailures.size()),
                        streamFailures.isEmpty() ? "pass" : "fail"))
                .append("</div>");

        if (apiFailures.isEmpty() && pageFailures.isEmpty() && streamFailures.isEmpty()) {
            html.append("<h2>Result</h2><p class=\"ok\">No API failures detected.<br>")
                    .append("No page failures detected.<br>No stream failures detected.</p>");
        }

        if (!statuses.isEmpty()) {
            html.append("<h2>Failed API status distribution</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Status</th><th>Count</th></tr>");
            for (Map.Entry<Integer, Integer> entry : statuses.entrySet()) {
                html.append("<tr><td class=\"s e\">").append(entry.getKey()).append("</td><td>")
                        .append(entry.getValue()).append("</td></tr>");
            }
            html.append("</table></div>");
        }

        if (!pageFailures.isEmpty()) {
            html.append("<h2>Failed pages</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Page</th><th>Iter</th><th>Type</th><th>Failed APIs</th>")
                    .append("<th>Reason</th><th>Screenshot</th></tr>");
            for (PageFailure failure : pageFailures) {
                html.append("<tr><td>").append(escape(failure.page())).append("</td><td>")
                        .append(failure.iteration()).append("</td><td class=\"s e\">")
                        .append(escape(failure.failureType())).append("</td><td>")
                        .append(failure.failedApis()).append("</td><td>")
                        .append(escape(value(failure.reason()))).append("</td><td><code>")
                        .append(escape(value(failure.screenshot()))).append("</code></td></tr>");
            }
            html.append("</table></div>");
        }

        if (!apiFailures.isEmpty()) {
            html.append("<h2>Failed APIs</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Time</th><th>Iter</th><th>Page</th><th>Method</th><th>Status</th>")
                    .append("<th>URL</th><th>Count</th><th>Screenshot</th></tr>");
            for (ApiMonitor.ApiFailure failure : apiFailures) {
                html.append("<tr><td>").append(escape(failure.timestamp())).append("</td><td>")
                        .append(failure.iteration()).append("</td><td>")
                        .append(escape(failure.pageName())).append("</td><td>")
                        .append(escape(failure.method())).append("</td><td class=\"s e\">")
                        .append(failure.status()).append(" ").append(escape(failure.statusText()))
                        .append("</td><td><code>").append(escape(failure.url())).append("</code></td><td>")
                        .append(failure.occurrences()).append("</td><td><code>")
                        .append(escape(value(screenshotFor(failure.pageName(), failure.iteration()))))
                        .append("</code></td></tr>");
            }
            html.append("</table></div>");
        }

        if (!streamFailures.isEmpty()) {
            html.append("<h2>Stream failures</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Time</th><th>Iter</th><th>Page</th><th>Device</th><th>Failure</th>")
                    .append("<th>Resolution</th><th>Ready</th><th>Count</th><th>Screenshot</th></tr>");
            for (StreamFailure failure : streamFailures) {
                html.append("<tr><td>").append(escape(failure.timestamp)).append("</td><td>")
                        .append(failure.iteration).append("</td><td>").append(escape(failure.page))
                        .append("</td><td>").append(escape(failure.device)).append("</td><td class=\"s e\">")
                        .append(escape(failure.failure)).append("</td><td>")
                        .append(escape(failure.resolution)).append("</td><td>").append(failure.readyState)
                        .append("</td><td>").append(failure.occurrences()).append("</td><td><code>")
                        .append(escape(value(failure.screenshot))).append("</code></td></tr>");
            }
            html.append("</table></div>");
        }

        if (!timeline.isEmpty()) {
            html.append("<h2>Failure timeline</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Time</th><th>Type</th><th>Detail</th></tr>");
            for (String[] entry : timeline) {
                html.append("<tr><td>").append(escape(entry[0])).append("</td><td class=\"s e\">")
                        .append(escape(entry[1])).append("</td><td>").append(escape(entry[2]))
                        .append("</td></tr>");
            }
            html.append("</table></div>");
        }

        html.append("</div></body></html>");
        write(run.finalReportDirectory().resolve("soak-test-report.html"), html.toString());
    }

    private static String card(String label, String number, String tone) {
        return "<div class=\"card\"><div class=\"n " + tone + "\">" + escape(number)
                + "</div><div class=\"l\">" + escape(label) + "</div></div>";
    }

    private static void writeJsonSummary(SoakRunContext run, String overallResult, int iterations,
                                         LocalDateTime start, LocalDateTime end, String duration,
                                         List<ApiMonitor.PageApiResult> pageResults,
                                         List<PageFailure> pageFailures,
                                         List<ApiMonitor.ApiFailure> apiFailures,
                                         List<StreamFailure> streamFailures, int totalResponses,
                                         int successfulApis, int failedApis, int timeouts,
                                         Map<Integer, Integer> statuses) {
        try {
            int pagesFailed = pageFailures.size();
            int pagesTested = Math.max(pageResults.size(), pagesFailed + PAGES_PASSED.get());

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("overallResult", overallResult);
            summary.put("startTime", start.format(TIMESTAMP));
            summary.put("endTime", end.format(TIMESTAMP));
            summary.put("duration", duration);
            summary.put("iterations", iterations);
            summary.put("evidenceFolder", SoakRunContext.relative(run.runDirectory().toString()));

            Map<String, Object> pages = new LinkedHashMap<>();
            pages.put("total", pagesTested);
            pages.put("passed", Math.max(0, pagesTested - pagesFailed));
            pages.put("failed", pagesFailed);
            List<String> failedPageNames = new ArrayList<>();
            for (PageFailure failure : pageFailures) {
                failedPageNames.add(failure.page());
            }
            pages.put("failedPages", failedPageNames);
            summary.put("pages", pages);

            Map<String, Object> api = new LinkedHashMap<>();
            api.put("totalRequests", totalResponses + timeouts);
            api.put("successful", successfulApis);
            api.put("failed", failedApis);
            api.put("timeouts", timeouts);
            api.put("statusDistribution", statuses);
            summary.put("api", api);

            summary.put("streamFailureCount", streamFailures.size());
            summary.put("apiFailureCount", apiFailures.size());

            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(run.finalReportDirectory().resolve("soak-test-summary.json").toFile(), summary);
        } catch (Exception exception) {
            System.err.println("[SOAK REPORT] Could not write soak-test-summary.json: " + exception.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            System.err.println("[SOAK REPORT] Could not write " + file + ": " + exception.getMessage());
        }
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "N/A" : text;
    }

    private static String trim(String text) {
        if (text == null) {
            return "N/A";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() > 300 ? single.substring(0, 300) + " ...[truncated]" : single;
    }

    private static String shortUrl(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            return path == null || path.isBlank() ? url : path;
        } catch (Exception exception) {
            return url;
        }
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /** Clears collected failures so a fresh execution starts clean. */
    public static void reset() {
        PAGE_FAILURES.clear();
        STREAM_FAILURES.clear();
        STREAM_INDEX.clear();
        SCREENSHOTS.clear();
        PAGES_PASSED.set(0);
    }
}
