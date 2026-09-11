package com.vigilx.soak;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.ScreenshotType;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.monitoring.ApiMonitor;
import com.vigilx.monitoring.LiveViewMonitor;
import com.vigilx.monitoring.PageApiTracker;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.reporting.SoakConsolidatedReportGenerator;
import com.vigilx.reporting.SoakReporter;
import com.vigilx.reporting.SoakRunContext;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.utils.LoggerUtils;
import com.vigilx.utils.WaitUtils;

/** Runs one health check per fresh browser, or repeats it until the configured duration ends. */
public final class SoakHealthCheckRunner {
    private static final Logger LOG = LoggerUtils.getLogger(SoakHealthCheckRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS");
    /** Soak cycle number, stamped onto every API failure so repeats across cycles stay distinguishable. */
    private static final java.util.concurrent.atomic.AtomicInteger ITERATION =
            new java.util.concurrent.atomic.AtomicInteger();
    private SoakHealthCheckRunner() { }

    /**
     * Marks "every configured validation ran and the application was found unhealthy" - as opposed to
     * a genuine infrastructure/framework problem. Thrown only by the two checks at the end of the try
     * block below, after every validation, logout and browser cleanup has already completed; the
     * catch block uses the type (not the message) to tell the two apart, so {@link SoakResult#overall}
     * still ends up "FAIL" exactly as before while {@link SoakResult#executionStatus} stays
     * "COMPLETED" instead of being misreported as an execution error.
     */
    private static final class SoakValidationFailedException extends RuntimeException {
        SoakValidationFailedException(String message) {
            super(message);
        }
    }

    public static SoakResult runOnce() {
        SoakTestConfig config = SoakTestConfig.load();
        int iteration = ITERATION.incrementAndGet();
        ApiMonitor.setIteration(iteration);
        // Fresh timestamped evidence folder; earlier runs are never deleted.
        SoakRunContext.start();
        SoakResult result = new SoakResult();
        result.executionId = "SOAK-" + LocalDateTime.now().format(ID);
        result.camera = config.cameraName();
        long started = System.nanoTime();
        Path evidence = Path.of(config.outputDirectory(), result.executionId);
        Page page = null;
        BrowserContext context = null;
        boolean tracing = false;
        try {
            Files.createDirectories(evidence.resolve("screenshots"));
            Files.createDirectories(evidence.resolve("trace"));
            page = PlaywrightFactory.initializeBrowser();
            page.setDefaultTimeout(ConfigReader.getInt("soak.page.timeout.ms"));
            page.onResponse(response -> {
                int status = response.status();
                if (status == 500 || status == 502) {
                    result.apiFailures.add(response.request().method() + " " + status + " " + response.url());
                }
            });
            context = PlaywrightFactory.getContext();
            page.navigate(ConfigReader.get("base.url"));
            WaitUtils.waitAfterPageNavigation(page);
            context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));
            tracing = true;
            LOG.info("[SOAK] {} started", result.executionId);
            DashboardPage dashboard = new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            result.login = "PASS";
            LOG.info("[SOAK] Login completed | status={}", result.login);
            if (!dashboard.isDashboardLoaded()) throw new IllegalStateException("Dashboard URL was not reached after login");
            result.dashboard = "PASS";
            LOG.info("[SOAK] Dashboard validation completed | status={}", result.dashboard);
            ApplicationHealthPage healthPages = new ApplicationHealthPage(page);
            String appUrl = ConfigReader.get("base.url").replace("/onboarding", "");
            validatePage(page, result, "Project Hierarchy", healthPages::validateProjectHierarchy);
            validatePage(page, result, "Devices", healthPages::validateDevices);
            validatePage(page, result, "Device Tabs", healthPages::validateDeviceTabs);
            if (config.alertEnabled()) {
                Path alertScreenshot = evidence.resolve("screenshots/video-alert-failure.png");
                LOG.info("[SOAK] Alerts V1 and Video Alert validation started");
                boolean alertPassed = healthPages.validateAlerts(appUrl, alertScreenshot);
                // soak.alert.required=false was never honoured: a video alert that carries only
                // images still failed the whole run. The status must not start with FAIL, since
                // that is what marks the run failed further down.
                String alertStatus = alertPassed ? "PASS"
                        : config.alertRequired() ? "FAIL"
                                : "WARN: Video Alert validation failed (soak.alert.required=false)";
                result.alerts = alertPassed ? "PASS" : config.alertRequired() ? "FAIL" : "WARN";
                result.pageResults.put("Alerts V1 and Video Alert", alertStatus);
                LOG.info("[SOAK] Alerts V1 and Video Alert validation completed | status={}", alertStatus);
                if (!alertPassed) {
                    result.screenshot = "screenshots/video-alert-failure.png";
                    if (config.alertRequired()) {
                        result.error = appendError(result.error, "Video Alert validation failed");
                    }
                }
            }
            validatePage(page, result, "License", healthPages::validateSettings);
            validatePage(page, result, "Users & Roles", healthPages::validateUsersAndRoles);
            validatePage(page, result, "Organisation", healthPages::validateOrganisation);
            if (config.liveEnabled()) {
                // Single Live View execution: Live_view already walks every camera three times over
                // ~75s, so the former second pass below this block was redundant.
                long streamStarted = System.nanoTime();
                validatePage(page, result, "Live View", () -> healthPages.validateLiveView(appUrl));
                boolean liveViewPassed = "PASS".equals(result.pageResults.get("Live View"));
                result.liveView = liveViewPassed ? "PASS" : "FAIL";
                result.stream = liveViewPassed ? "PASS" : "FAIL";
                if (liveViewPassed) {
                    result.streamStartupTimeMs = (System.nanoTime() - streamStarted) / 1_000_000;
                } else {
                    result.error = appendError(result.error, "Live View stream validation failed");
                    captureFailureScreenshot(page, evidence, result, config, "live-view-failure.png");
                }

                // Continuous 30-60s watch: re-validates APIs and every stream on each pass.
                LiveViewMonitor.MonitorResult monitored = new LiveViewMonitor(page).monitor();
                result.pageResults.put("Live View Continuous Monitoring",
                        monitored.isPassed() ? "PASS"
                                : "FAIL: " + monitored.cameraFailures() + " camera, "
                                        + monitored.apiFailures() + " API failure(s) over "
                                        + monitored.iterations() + " iteration(s)");
                if (!monitored.isPassed()) {
                    result.error = appendError(result.error, "Live View continuous monitoring detected failures");
                }
            }
            validatePage(page, result, "Map", () -> healthPages.validateMap(appUrl));
            validatePage(page, result, "Map Camera Validation", () -> healthPages.validateMapCameras(appUrl));
            validatePage(page, result, "Archive", () -> healthPages.validateArchive(appUrl));
            // Archive Camera Validation already does the whole flow in one pass: open Add Camera,
            // filter to Active, pick one random online device, add it once, then watch its stream for
            // the full monitoring window. This used to be followed by a second addCameraToPlayback()
            // call that added a different, hardcoded camera on top of it, then a third re-navigation
            // to Archive that only checked the "Playback" heading was visible - not the stream itself.
            // One add, one watch, one verdict.
            long playbackStarted = System.nanoTime();
            validatePage(page, result, "Archive Camera Validation", () -> healthPages.validateArchiveCameras(appUrl));
            if (config.playbackEnabled()) {
                result.playback = "PASS".equals(result.pageResults.get("Archive Camera Validation")) ? "PASS" : "FAIL";
                result.playbackStartupTimeMs = (System.nanoTime() - playbackStarted) / 1_000_000;
            }
            // Archive is the last validation; logout follows immediately. Settings, Users & Roles and
            // Organisation already ran once above, so they are not repeated here.
            if (config.logoutEnabled()) {
                LOG.info("[SOAK] Logout started");
                if (dashboard.logoutSafely(appUrl)) {
                    result.logout = "PASS";
                    LOG.info("[SOAK] Logout completed | status=PASS");
                } else {
                    // Session still ends when the isolated context is disposed, but the UI control failed.
                    result.logout = "FAIL";
                    result.error = appendError(result.error, "Logout control could not be used");
                    LOG.warn("[SOAK] Logout failed; closing the isolated browser context instead.");
                }
                // The session is over once logout returns, so the browser is shut down here instead
                // of staying open while evidence and reports are written. Tracing has to stop first:
                // stopping it needs a live context.
                if (tracing) {
                    try {
                        Path trace = evidence.resolve("trace/trace.zip");
                        context.tracing().stop(new Tracing.StopOptions().setPath(trace));
                        result.trace = "trace/trace.zip";
                    } catch (Exception exception) {
                        LOG.warn("[SOAK] Trace could not be saved before closing the browser: {}",
                                exception.getMessage());
                    }
                    tracing = false;
                }
                PlaywrightFactory.closeBrowser();
                context = null;
                page = null;
                LOG.info("[SOAK] Browser closed after logout");
            }
            LOG.info("[SOAK] All configured validations completed");
            if (result.pageResults.values().stream().anyMatch(value -> value.startsWith("FAIL"))) {
                throw new SoakValidationFailedException("One or more read-only page validations failed");
            }
            if (!result.apiFailures.isEmpty()) {
                throw new SoakValidationFailedException("HTTP 500/502 responses detected: " + result.apiFailures.size());
            }
            result.overall = "PASS";
        } catch (Exception exception) {
            result.error = exception.getMessage();
            if (exception instanceof SoakValidationFailedException) {
                // Every validation, logout and browser-close above already ran to completion; this is
                // the application being unhealthy, not the automation failing. executionStatus stays
                // "COMPLETED" - only overall (already "FAIL" by default) carries this outcome.
                LOG.warn("[SOAK] {} completed | Validation status=FAIL: {}", result.executionId, result.error);
            } else {
                // Anything else here is exactly what validatePage()/LiveViewMonitor could not isolate:
                // a genuine infrastructure/framework problem (browser crash, Playwright init failure,
                // an unexpected bug), not the application under test - reported distinctly so the two
                // are never confused downstream.
                result.executionStatus = "ERROR";
                result.executionError = exception.getMessage();
                LOG.error("[SOAK] {} EXECUTION ERROR (infrastructure/framework, not an application validation): {}",
                        result.executionId, result.error, exception);
            }
            try {
                if (page != null && config.failureScreenshot()) {
                    Path screenshot = evidence.resolve("screenshots/failure.png");
                    page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setType(ScreenshotType.PNG));
                    result.screenshot = "screenshots/failure.png";
                }
            } catch (Exception ignored) { }
        } finally {
            result.durationMs = (System.nanoTime() - started) / 1_000_000;
            try {
                // Consolidated API failure document, stored beside this execution's other evidence.
                if (ApiMonitor.writeReportToQuietly(evidence) != null) {
                    result.apiFailureLog = "api-failures.log";
                }
                // Failure-centric soak reports under target/soak-test/run-<timestamp>/. The execution
                // ID and full per-page results ride along so the consolidated report below can
                // correlate and aggregate runs without re-deriving anything already computed here.
                SoakReporter.writeAll(result.overall, iteration, result.executionId, result.pageResults);
                if (tracing && context != null) {
                    Path trace = evidence.resolve("trace/trace.zip");
                    context.tracing().stop(new Tracing.StopOptions().setPath(trace));
                    result.trace = "trace/trace.zip";
                }
                JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("result.json").toFile(), result);
                LOG.info("[SOAK REPORT] Final report generated");
            } catch (Exception ignored) { }
            try {
                // Additive reporting layer only: rescans every completed run under target/soak-test
                // and rewrites the multi-run report. Never affects pass/fail or any flow above.
                SoakConsolidatedReportGenerator.generate();
                LOG.info("[SOAK REPORT] Consolidated report updated");
            } catch (Exception ignored) { }
            PlaywrightFactory.closeBrowser();
        }
        LOG.info("[SOAK] Execution completed");
        LOG.info("[SOAK] Validation status: {}", result.overall);
        LOG.info("[SOAK] Execution status: {}", result.executionStatus);
        return result;
    }

    public static void runPeriodically() {
        SoakTestConfig config = SoakTestConfig.load();
        long end = System.nanoTime() + config.durationHours() * 3_600_000_000_000L;
        do {
            runOnce();
            long remaining = end - System.nanoTime();
            if (remaining <= 0) break;
            try { Thread.sleep(Math.min(config.intervalMinutes() * 60_000L, remaining / 1_000_000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        } while (true);
    }

    /**
     * Runs one page's validation, then blocks until that page's API traffic has drained before
     * returning - so the next page never starts while calls are still in flight.
     *
     * <p>The UI check and the API check are combined: a page passes only when both pass. Set
     * {@code api.page.validation.strict=false} to keep API failures reported but non-fatal.
     */
    private static void validatePage(Page page, SoakResult result, String name, PageCheck check) {
        LOG.info("[SOAK] {} validation started", name);
        PageApiTracker tracker = PageApiTracker.start(page, name);
        boolean uiPassed = false;
        String uiError = null;
        try {
            uiPassed = check.validate();
        } catch (RuntimeException exception) {
            uiError = exception.getMessage();
            LOG.error("[SOAK] {} validation failed: {}", name, exception.getMessage(), exception);
        }

        // Always drain the page's APIs, even when the UI check already failed. The UI outcome is
        // passed in so a healthy page is not screenshotted just because a background API failed.
        ApiMonitor.PageApiResult apiResult = tracker.finish(uiPassed && uiError == null);
        boolean apiPassed = apiResult.isPassed() || !strictApiValidation();

        String status;
        if (uiError != null) {
            status = "FAIL: " + uiError;
        } else if (uiPassed && apiPassed) {
            status = "PASS";
        } else if (!uiPassed) {
            status = "FAIL";
        } else {
            status = "FAIL (API): " + apiResult.failed() + " failed, " + apiResult.timeouts() + " timed out";
        }

        result.pageResults.put(name, status);

        // Failure-centric reporting: only failures produce a record; passes are counted only.
        if (status.startsWith("FAIL")) {
            String failureType = uiError != null ? "EXCEPTION"
                    : (!uiPassed ? "UI_FAILURE" : "API_FAILURE");
            String reason = uiError != null ? uiError
                    : (!uiPassed ? name + " UI validation returned false"
                            : name + " had " + apiResult.failed() + " failed API(s) and "
                                    + apiResult.timeouts() + " timeout(s)");
            SoakReporter.recordPageFailure(name, failureType, apiResult.failed(), null, reason);
        } else {
            SoakReporter.recordPagePassed();
        }

        LOG.info("[SOAK] {} validation completed | status={} | apiRequests={} apiFailed={} apiTimeouts={}",
                name, status, apiResult.requests(), apiResult.failed(), apiResult.timeouts());
    }

    /** When true (default) a page's API failures fail the page; otherwise they are report-only. */
    private static boolean strictApiValidation() {
        try {
            return Boolean.parseBoolean(ConfigReader.getOrDefault("api.page.validation.strict", "true"));
        } catch (Exception exception) {
            return true;
        }
    }

    private static String appendError(String existing, String next) {
        if (existing == null || existing.isBlank()) return next;
        if (next == null || next.isBlank()) return existing;
        return existing + "; " + next;
    }

    private static void captureFailureScreenshot(Page page, Path evidence, SoakResult result, SoakTestConfig config, String fileName) {
        try {
            if (page != null && config.failureScreenshot()) {
                Path screenshot = evidence.resolve("screenshots/" + fileName);
                page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setType(ScreenshotType.PNG));
                result.screenshot = "screenshots/" + fileName;
            }
        } catch (Exception ignored) { }
    }

    @FunctionalInterface
    private interface PageCheck { boolean validate(); }
}
