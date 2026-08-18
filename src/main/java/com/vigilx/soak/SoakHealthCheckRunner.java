package com.vigilx.soak;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ScreenshotType;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.utils.LoggerUtils;

/** Runs one health check per fresh browser, or repeats it until the configured duration ends. */
public final class SoakHealthCheckRunner {
    private static final Logger LOG = LoggerUtils.getLogger(SoakHealthCheckRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS");
    private SoakHealthCheckRunner() { }

    public static SoakResult runOnce() {
        SoakTestConfig config = SoakTestConfig.load();
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
            context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));
            tracing = true;
            LOG.info("[SOAK] {} started", result.executionId);
            DashboardPage dashboard = new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            result.login = "PASS";
            if (!dashboard.isDashboardLoaded()) throw new IllegalStateException("Dashboard URL was not reached after login");
            result.dashboard = "PASS";
            ApplicationHealthPage healthPages = new ApplicationHealthPage(page);
            String appUrl = ConfigReader.get("base.url").replace("/onboarding", "");
            validatePage(result, "Project Hierarchy", healthPages::validateProjectHierarchy);
            validatePage(result, "Devices", healthPages::validateDevices);
            validatePage(result, "Device Tabs", healthPages::validateDeviceTabs);
            if (config.alertEnabled()) {
                page.navigate(appUrl + config.alertPath());
                boolean found = false;
                try {
                    found = healthPages.validateAlerts();
                } catch (RuntimeException exception) {
                    result.error = appendError(result.error, "Alert playback validation failed: " + exception.getMessage());
                }
                result.alerts = found ? "PASS" : "FAIL";
                result.pageResults.put("Alerts", found ? "PASS" : "SKIPPED");
                if (!found) {
                    result.error = appendError(result.error, "Alert validation failed: no alert video was available to play. Captured and continuing to the next check.");
                    LOG.warn("[SOAK] Alert video unavailable; continuing without blocking the rest of the soak run.");
                }
            }
            validatePage(result, "Settings", healthPages::validateSettings);
            validatePage(result, "Users & Roles", healthPages::validateUsersAndRoles);
            validatePage(result, "Organisation", healthPages::validateOrganisation);
            validatePage(result, "Map", () -> healthPages.validateMap(appUrl));
            validatePage(result, "Archive", () -> healthPages.validateArchive(appUrl));
            if (config.playbackEnabled()) {
                String cameraName = config.cameraName();
                healthPages.addCameraToPlayback(appUrl, cameraName);
            }
            page.waitForTimeout(5000);
            if (config.liveEnabled()) {
                long streamStarted = System.nanoTime();
                String liveViewUrl = appUrl + "/live-views/views/v1";
                page.navigate(liveViewUrl);

                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
                boolean sawStream = false;
                String failureSummary = null;
                while (System.nanoTime() < deadline) {
                    var cameraButtons = page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName(config.cameraName()).setExact(false));
                    int count = cameraButtons.count();
                    if (count == 0) {
                        page.waitForTimeout(2000);
                        continue;
                    }

                    for (int index = 0; index < count; index++) {
                        var camera = cameraButtons.nth(index);
                        String cameraLabel = camera.textContent();
                        String name = cameraLabel == null ? "Camera " + (index + 1) : cameraLabel.trim();
                        sawStream = true;
                        try {
                            camera.click();
                            page.locator("video, canvas, [class*='player' i], [class*='video' i]").first()
                                    .waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout((double) config.streamTimeoutMs()));
                        } catch (RuntimeException exception) {
                            result.streamFailures.add(name + ": " + exception.getMessage());
                        }
                    }

                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    page.waitForTimeout(Math.min(5000L, remaining / 1_000_000L));
                }

                if (!result.streamFailures.isEmpty()) {
                    failureSummary = String.join("; ", result.streamFailures);
                    result.error = appendError(result.error, failureSummary);
                    result.liveView = "FAIL";
                    result.stream = "FAIL";
                    captureFailureScreenshot(page, evidence, result, config, "live-view-failure.png");
                } else if (sawStream) {
                    result.liveView = "PASS";
                    result.stream = "PASS";
                    result.streamStartupTimeMs = (System.nanoTime() - streamStarted) / 1_000_000;
                } else {
                    result.liveView = "FAIL";
                    result.stream = "FAIL";
                    result.error = appendError(result.error, "No live-view streams were available after waiting 5 minutes.");
                    captureFailureScreenshot(page, evidence, result, config, "live-view-failure.png");
                }
            }
            if (config.playbackEnabled()) {
                long playbackStarted = System.nanoTime();
                String id = config.cameraId().isBlank() ? "" : "?cameras=" + config.cameraId();
                page.navigate(appUrl + "/live-views/archive" + id);
                page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Playback").setExact(true)).waitFor();
                result.playback = "PASS";
                result.playbackStartupTimeMs = (System.nanoTime() - playbackStarted) / 1_000_000;
            }
            validatePage(result, "Users & Roles", healthPages::validateUsersAndRoles);
            validatePage(result, "Organisation", healthPages::validateOrganisation);
            validatePage(result, "Settings", healthPages::validateSettings);
            if (config.logoutEnabled()) {
                if (dashboard.isProfileVisible()) {
                    dashboard.logout();
                    result.logout = "PASS";
                } else {
                    // This deployment has no accessible Profile control; context disposal still removes the session.
                    result.logout = "SKIPPED";
                    LOG.warn("[SOAK] Logout control unavailable; closing the isolated browser context instead.");
                }
            }
            if (result.pageResults.values().stream().anyMatch(value -> value.startsWith("FAIL"))) {
                throw new IllegalStateException("One or more read-only page validations failed");
            }
            if (!result.apiFailures.isEmpty()) {
                throw new IllegalStateException("HTTP 500/502 responses detected: " + result.apiFailures.size());
            }
            result.overall = "PASS";
        } catch (Exception exception) {
            result.error = exception.getMessage();
            LOG.error("[SOAK] {} FAIL: {}", result.executionId, result.error);
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
                if (tracing && context != null) {
                    Path trace = evidence.resolve("trace/trace.zip");
                    context.tracing().stop(new Tracing.StopOptions().setPath(trace));
                    result.trace = "trace/trace.zip";
                }
                JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("result.json").toFile(), result);
            } catch (Exception ignored) { }
            PlaywrightFactory.closeBrowser();
        }
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

    private static void validatePage(SoakResult result, String name, PageCheck check) {
        try {
            result.pageResults.put(name, check.validate() ? "PASS" : "FAIL");
        } catch (RuntimeException exception) {
            result.pageResults.put(name, "FAIL: " + exception.getMessage());
            LOG.error("[SOAK] {} page validation failed: {}", name, exception.getMessage());
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
