package com.vigilx.monitoring;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.reporting.SoakReporter;
import com.vigilx.reporting.SoakRunContext;
import com.vigilx.utils.ScreenshotUtils;

/**
 * Gates page transitions on API completion.
 *
 * <p>Usage is start / act / finish:
 * <pre>
 *     PageApiTracker tracker = PageApiTracker.start(page, "Devices");
 *     boolean uiPassed = doTheExistingValidation();
 *     ApiMonitor.PageApiResult apiResult = tracker.finish();
 * </pre>
 *
 * <p>{@link #finish()} blocks until the page's API requests have drained (or the configured
 * timeout expires), so the caller cannot navigate away while calls are still in flight.
 *
 * <p><strong>Waiting pumps the Playwright event loop.</strong> Playwright Java dispatches network
 * events on the thread that owns the connection, so this class waits with
 * {@code page.waitForTimeout(...)} rather than {@code Thread.sleep(...)} - a plain sleep would
 * block the very thread that delivers the responses being waited for.
 *
 * <p><strong>Parallel execution.</strong> The page/iteration labels in {@link ApiMonitor} are
 * process-wide, matching this framework's single shared {@code PlaywrightFactory} page. Running
 * suites in parallel against one JVM would interleave those labels; per-context tracking would be
 * needed first.
 */
public final class PageApiTracker {

    private static final int DEFAULT_PAGE_TIMEOUT_MS = 30000;
    private static final int DEFAULT_QUIET_PERIOD_MS = 1200;
    private static final int POLL_INTERVAL_MS = 250;
    private static final String DEFAULT_SCREENSHOT_ROOT = "target/soak-test/screenshots";

    private final Page page;
    private final String pageName;
    private final int failuresBefore;
    private final int responsesBefore;
    private final long startedNanos;

    private PageApiTracker(Page page, String pageName) {
        this.page = page;
        this.pageName = pageName;
        this.failuresBefore = ApiMonitor.getDistinctFailureCount();
        this.responsesBefore = ApiMonitor.getTotalResponses();
        this.startedNanos = System.nanoTime();
    }

    /**
     * Begins tracking for a page. Call this <em>before</em> navigating or acting, so requests fired
     * during navigation are attributed to the right page.
     */
    public static PageApiTracker start(Page page, String pageName) {
        ApiMonitor.setCurrentPage(pageName);
        ApiMonitor.setCurrentOperation("Load " + pageName);
        return new PageApiTracker(page, pageName);
    }

    /** Labels subsequent failures with a finer-grained operation inside the same page. */
    public void operation(String operation) {
        ApiMonitor.setCurrentOperation(operation);
    }

    /**
     * Waits for this page's API traffic to drain, then produces and records the result. Never
     * throws; on any internal problem it returns whatever was gathered so the soak continues.
     */
    public ApiMonitor.PageApiResult finish() {
        return finish(false);
    }

    /**
     * Waits for this page's API traffic to drain, then produces and records the result.
     *
     * <p>A screenshot is taken only when the page's UI validation failed. If the UI rendered
     * correctly and only a background API failed, an image of a healthy page proves nothing - the
     * failure is already fully described in the API log. Set
     * {@code api.page.screenshot.on.api.failure=true} to capture those too.
     *
     * @param uiPassed whether the page's own UI validation succeeded
     */
    public ApiMonitor.PageApiResult finish(boolean uiPassed) {
        List<String> pending;
        try {
            pending = waitForApis();
        } catch (Exception exception) {
            System.err.println("[PAGE API] Wait failed for " + pageName + ": " + exception.getMessage());
            pending = List.of();
        }

        ApiMonitor.PageApiResult result;
        try {
            result = ApiMonitor.buildPageResult(pageName, failuresBefore, responsesBefore, pending);
            ApiMonitor.recordPageResult(result);
            System.out.println(result.render());

            // Screenshot only when the UI itself is wrong; a healthy page is not useful evidence.
            if (!result.isPassed() && (!uiPassed || screenshotOnApiFailure())) {
                captureScreenshot(result);
            }
        } catch (Exception exception) {
            System.err.println("[PAGE API] Could not build the result for " + pageName + ": "
                    + exception.getMessage());
            return ApiMonitor.buildPageResult(pageName, failuresBefore, responsesBefore, List.of());
        }
        return result;
    }

    /**
     * Blocks until no API request has been in flight for a quiet period, or the timeout expires.
     *
     * @return the requests still outstanding at the deadline; empty when the page drained cleanly
     */
    private List<String> waitForApis() {
        long timeoutMs = pageTimeoutMs();
        long quietMs = quietPeriodMs();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        long quietSince = -1;

        while (System.nanoTime() < deadline) {
            int pending = ApiMonitor.pendingApiCount();

            if (pending == 0) {
                if (quietSince < 0) {
                    quietSince = System.nanoTime();
                } else if ((System.nanoTime() - quietSince) / 1_000_000 >= quietMs) {
                    long elapsed = (System.nanoTime() - startedNanos) / 1_000_000;
                    System.out.println("[PAGE API] " + pageName + " APIs settled in " + elapsed + "ms.");
                    return List.of();
                }
            } else {
                quietSince = -1;
            }

            // waitForTimeout pumps the Playwright message loop; Thread.sleep would starve it.
            try {
                page.waitForTimeout(POLL_INTERVAL_MS);
            } catch (Exception exception) {
                // The page may have closed underneath us; nothing more to wait for.
                return List.of();
            }
        }

        List<String> pending = ApiMonitor.pendingApiUrls();
        List<String> stalled = ApiMonitor.stalledApiUrls();
        if (!pending.isEmpty() || !stalled.isEmpty()) {
            System.err.println("[PAGE API TIMEOUT] " + pageName + " still had "
                    + (pending.size() + stalled.size()) + " request(s) after " + timeoutMs + "ms.");
            for (String url : pending) {
                System.err.println("    pending : " + url);
            }
            for (String url : stalled) {
                System.err.println("    stalled : " + url);
            }
        }
        return pending;
    }

    /**
     * Evidence for a failed page API validation, filed under the run's page-specific screenshot
     * folder and registered so the API failure records can cross-reference it.
     */
    private void captureScreenshot(ApiMonitor.PageApiResult result) {
        try {
            String root = ConfigReader.getOrDefault("soak.screenshot.root", DEFAULT_SCREENSHOT_ROOT);
            Path directory = Paths.get(root, SoakRunContext.screenshotFolder(pageName));
            String name = slug(pageName) + (result.timeouts() > 0 ? "-api-timeout" : "-api-failure");
            String path = ScreenshotUtils.captureTo(page, directory, name);
            SoakReporter.registerScreenshot(pageName, path);
        } catch (Exception exception) {
            System.err.println("[PAGE API] Screenshot failed for " + pageName + ": " + exception.getMessage());
        }
    }

    /** Opt-in: also screenshot pages whose UI was fine but whose background APIs failed. */
    private static boolean screenshotOnApiFailure() {
        try {
            return Boolean.parseBoolean(
                    ConfigReader.getOrDefault("api.page.screenshot.on.api.failure", "false"));
        } catch (Exception exception) {
            return false;
        }
    }

    private int pageTimeoutMs() {
        return intConfig("api.page.timeout.ms", DEFAULT_PAGE_TIMEOUT_MS);
    }

    private int quietPeriodMs() {
        return intConfig("api.page.quiet.ms", DEFAULT_QUIET_PERIOD_MS);
    }

    private static int intConfig(String key, int fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "page";
        }
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "page" : slug;
    }
}
