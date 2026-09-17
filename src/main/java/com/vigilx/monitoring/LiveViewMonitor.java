package com.vigilx.monitoring;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.reporting.SoakReporter;
import com.vigilx.utils.ScreenshotUtils;

/**
 * Continuous Live View monitoring for the soak run.
 *
 * <p>After the existing {@code Live_view} validation has confirmed the page loads, this class
 * watches the page for a configurable window (default 60s), re-checking every camera's media state
 * and every new API failure on each pass. It is additional to - not a replacement for - the
 * existing validation, which is left untouched.
 *
 * <p>Never throws: a camera or API problem is recorded and monitoring continues to the next
 * iteration, so a single bad stream cannot end the soak.
 */
public final class LiveViewMonitor {

    private static final int DEFAULT_DURATION_SECONDS = 30;
    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final double PLAYBACK_TOLERANCE_SECONDS = 0.2;
    private static final int PROGRESS_SAMPLE_MS = 1200;
    private static final String DEFAULT_SCREENSHOT_DIRECTORY = "target/soak-test/screenshots/live-view";

    private static final String SEPARATOR = "============================================================";

    private final Page page;
    private final Path screenshotDirectory;

    public LiveViewMonitor(Page page) {
        this.page = page;
        this.screenshotDirectory = Paths.get(
                ConfigReader.getOrDefault("liveview.screenshot.directory", DEFAULT_SCREENSHOT_DIRECTORY));
    }

    /** Aggregate outcome of the whole monitoring window. */
    public static final class MonitorResult {
        private int iterations;
        private int cameraChecks;
        private int cameraFailures;
        private int apiFailures;

        public int iterations() { return iterations; }
        public int cameraChecks() { return cameraChecks; }
        public int cameraFailures() { return cameraFailures; }
        public int apiFailures() { return apiFailures; }
        public boolean isPassed() { return cameraFailures == 0 && apiFailures == 0; }
    }

    /**
     * Watches Live View for the configured window, validating APIs and streams on every pass.
     *
     * @return the aggregate result; {@code isPassed()} is false if any camera or API failed
     */
    public MonitorResult monitor() {
        MonitorResult result = new MonitorResult();
        int durationSeconds = intConfig("soak.liveview.monitor.seconds", DEFAULT_DURATION_SECONDS);
        int intervalSeconds = Math.max(1, intConfig("soak.liveview.monitor.interval.seconds", DEFAULT_INTERVAL_SECONDS));

        System.out.println(SEPARATOR);
        System.out.println("LIVE VIEW CONTINUOUS MONITORING (" + durationSeconds + "s)");
        System.out.println(SEPARATOR);

        long deadline = System.nanoTime() + durationSeconds * 1_000_000_000L;
        int apiFailuresBefore = ApiMonitor.getDistinctFailureCount();

        while (System.nanoTime() < deadline) {
            result.iterations++;
            int iteration = result.iterations;
            ApiMonitor.setCurrentOperation("Live View monitor iteration " + iteration);

            System.out.println("--- Live View monitor iteration " + iteration + " ---");

            // 1. Every new API failure since the previous pass, not just the first one.
            int newApiFailures = reportNewApiFailures(apiFailuresBefore, iteration);
            if (newApiFailures > 0) {
                result.apiFailures += newApiFailures;
                capture("live-view-api-failure-iteration-" + iteration);
            }
            apiFailuresBefore = ApiMonitor.getDistinctFailureCount();

            // 2. Every camera's media state - bounded by the SAME deadline as the outer loop, so a
            // pass over many (or failing/slow) cameras cannot by itself push the whole window well
            // past its configured duration: confirmed live, a single iteration's per-camera checks
            // (each with its own ~1.2s progress sample, several evaluate() round-trips, and a
            // full-page screenshot per failure) can otherwise run for 40+ seconds even when
            // soak.liveview.monitor.seconds=30, since the deadline was previously only checked
            // between iterations, never within one.
            try {
                List<String> failures = validateCameras(iteration, result, deadline);
                for (String failure : failures) {
                    System.err.println("[LIVE VIEW CAMERA FAIL] " + failure);
                }
            } catch (Exception exception) {
                System.err.println("[LIVE VIEW MONITOR] Camera pass failed: " + exception.getMessage());
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                page.waitForTimeout(Math.min(intervalSeconds * 1000L, remaining / 1_000_000L));
            } catch (Exception exception) {
                break;
            }
        }

        System.out.println(SEPARATOR);
        System.out.println("LIVE VIEW MONITORING SUMMARY");
        System.out.println(SEPARATOR);
        System.out.println("Iterations      : " + result.iterations);
        System.out.println("Camera checks   : " + result.cameraChecks);
        System.out.println("Camera failures : " + result.cameraFailures);
        System.out.println("API failures    : " + result.apiFailures);
        System.out.println("RESULT          : " + (result.isPassed() ? "PASS" : "FAIL"));
        System.out.println(SEPARATOR);
        return result;
    }

    /** Prints each API failure recorded since the previous pass and returns how many were new. */
    private int reportNewApiFailures(int failuresBefore, int iteration) {
        try {
            List<ApiMonitor.ApiFailure> failures = ApiMonitor.getFailures();
            if (failures.size() <= failuresBefore) {
                return 0;
            }
            List<ApiMonitor.ApiFailure> fresh = failures.subList(failuresBefore, failures.size());
            for (ApiMonitor.ApiFailure failure : fresh) {
                System.err.println("[LIVE VIEW API FAIL] iteration " + iteration + " | "
                        + failure.method() + " " + failure.status() + " " + failure.statusText()
                        + " | " + failure.url());
            }
            return fresh.size();
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW MONITOR] Could not read API failures: " + exception.getMessage());
            return 0;
        }
    }

    /**
     * Validates every video element currently on the page; returns human-readable failures.
     *
     * @param deadline the same {@code System.nanoTime()} deadline the outer monitoring window uses -
     *                 once passed, no further camera is started (a camera already in progress is
     *                 always finished, never abandoned mid-check), so one slow/failing pass cannot
     *                 push the whole window well past its configured duration.
     */
    private List<String> validateCameras(int iteration, MonitorResult result, long deadline) {
        List<String> failures = new ArrayList<>();
        Locator videos = page.locator("video");
        int count = videos.count();

        if (count == 0) {
            result.cameraFailures++;
            failures.add("iteration " + iteration + ": no video elements present");
            capture("live-view-no-video-iteration-" + iteration);
            return failures;
        }

        for (int index = 0; index < count; index++) {
            if (index > 0 && System.nanoTime() >= deadline) {
                System.out.println("[LIVE VIEW MONITOR] Monitoring window elapsed mid-iteration "
                        + iteration + "; stopping after " + index + "/" + count + " camera(s) this pass.");
                break;
            }
            result.cameraChecks++;
            String camera = "camera-" + (index + 1);
            try {
                Locator video = videos.nth(index);
                if (!video.isVisible()) {
                    result.cameraFailures++;
                    failures.add(camera + ": video not visible");
                    capture("live-view-" + camera + "-not-visible-iteration-" + iteration);
                    continue;
                }

                String source = String.valueOf(video.evaluate("e => e.currentSrc || e.src || ''"));
                String mediaError = String.valueOf(video.evaluate(
                        "e => e.error ? ('code ' + e.error.code) : ''"));
                int readyState = ((Number) video.evaluate("e => e.readyState")).intValue();
                int width = ((Number) video.evaluate("e => e.videoWidth")).intValue();
                int height = ((Number) video.evaluate("e => e.videoHeight")).intValue();
                boolean paused = (Boolean) video.evaluate("e => e.paused");
                boolean ended = (Boolean) video.evaluate("e => e.ended");
                double startTime = ((Number) video.evaluate("e => e.currentTime")).doubleValue();

                page.waitForTimeout(PROGRESS_SAMPLE_MS);
                double endTime = ((Number) video.evaluate("e => e.currentTime")).doubleValue();
                boolean progressed = endTime > startTime + PLAYBACK_TOLERANCE_SECONDS;

                String reason = null;
                if (source == null || source.isBlank()) {
                    reason = "no media source";
                } else if (mediaError != null && !mediaError.isBlank() && !"null".equals(mediaError)) {
                    reason = "media error: " + mediaError;
                } else if (readyState < 2) {
                    reason = "readyState " + readyState;
                } else if (width <= 0 || height <= 0) {
                    reason = "resolution " + width + "x" + height;
                } else if (paused || ended) {
                    reason = "stream " + (ended ? "ended" : "paused");
                } else if (!progressed) {
                    reason = "currentTime did not progress (" + startTime + " -> " + endTime + ")";
                }

                if (reason != null) {
                    result.cameraFailures++;
                    failures.add(camera + ": " + reason
                            + " | readyState=" + readyState
                            + " resolution=" + width + "x" + height
                            + " currentTime=" + startTime + "->" + endTime
                            + " source=" + (source == null || source.isBlank() ? "<empty>" : source));
                    // One screenshot per distinct failure, not one per monitoring pass.
                    String shot = SoakReporter.shouldCaptureScreenshot("Live View", camera, reason)
                            ? capture("live-view-" + camera + "-failed-iteration-" + iteration)
                            : null;
                    SoakReporter.recordStreamFailure("Live View", camera, "FOUND", "YES",
                            source == null || source.isBlank() ? "MISSING" : "PRESENT", readyState,
                            width + "x" + height, paused, startTime, reason, shot);
                }

            } catch (Exception exception) {
                result.cameraFailures++;
                failures.add(camera + ": validation error " + exception.getMessage());
                capture("live-view-" + camera + "-exception-iteration-" + iteration);
            }
        }
        return failures;
    }

    private String capture(String name) {
        return ScreenshotUtils.captureTo(page, screenshotDirectory, slug(name));
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
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
