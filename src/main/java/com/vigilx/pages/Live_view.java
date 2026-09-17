package com.vigilx.pages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

public class Live_view extends BasePage {

        public Live_view(Page page) {
                super(page);
        }

     public boolean validateLiveView(String baseUrl) {

    String timestamp = java.time.LocalDateTime.now()
            .toString()
            .replace(":", "-")
            .replace(".", "-");

    Path screenshotDir = Paths.get("target/screenshots/live-view/" + timestamp);

    boolean overallPassed = true;

    try {

        System.out.println("=================================================");
        System.out.println("LIVE VIEW VALIDATION STARTED");
        System.out.println("=================================================");

        // ---------------------------------------------------------
        // 1. Navigate to Live View
        // ---------------------------------------------------------

        navigateTo(baseUrl + "/live-views/views/v1");

        System.out.println("[INFO] Live View opened.");

        // ---------------------------------------------------------
        // 2. Validate Live View panel
        // ---------------------------------------------------------

        Locator operatorPanel =
                page.locator(".operator-panel__body");

        try {
            operatorPanel.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(15000));
        } catch (Exception exception) {

            System.err.println(
                    "[FAIL] Live View operator panel is not visible."
            );

            captureLiveViewScreenshot(
                    screenshotDir,
                    "live-view-panel-failure"
            );

            return false;
        }

        System.out.println(
                "[PASS] Live View operator panel is visible."
        );

        // ---------------------------------------------------------
        // 3. Find camera containers once the Live View panel is ready
        // ---------------------------------------------------------

        Locator cameraCells =
                page.getByRole(AriaRole.GRIDCELL);

        try {
            cameraCells.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(15000));
        } catch (Exception exception) {
            System.err.println("[FAIL] Live View camera tiles did not become visible: "
                    + exception.getMessage());
            captureLiveViewScreenshot(screenshotDir, "camera-tiles-not-ready");
            return false;
        }

        int cameraCount = cameraCells.count();

        System.out.println(
                "[INFO] Camera/grid cells found: "
                        + cameraCount
        );

        if (cameraCount == 0) {

            System.err.println(
                    "[FAIL] No camera tiles found."
            );

            captureLiveViewScreenshot(
                    screenshotDir,
                    "no-camera-found"
            );

            return false;
        }

        // ---------------------------------------------------------
        // 3b. Distinguish "view has no cameras" from "streams are broken"
        // ---------------------------------------------------------

        // A saved view with nothing assigned still renders empty "Add Camera" tiles. Checking each
        // tile then reports a missing stream per tile, which reads as a broken streaming service
        // rather than an unconfigured view.
        int tilesWithMedia = page.locator("[role='gridcell'] video, [role='gridcell'] canvas").count();

        if (tilesWithMedia == 0) {

            System.err.println(
                    "[FAIL] The opened Live View has no cameras assigned: "
                            + cameraCount
                            + " empty tile(s). Assign cameras to the default view before the soak run."
            );

            captureLiveViewScreenshot(
                    screenshotDir,
                    "view-has-no-cameras"
            );

            return false;
        }

        // ---------------------------------------------------------
        // 4. Validate every camera's stream
        // ---------------------------------------------------------

        // A single pass is enough here: this confirms the stream becomes available. Sustained
        // availability over time is the dedicated LiveViewMonitor's job (run right after this
        // returns), not a second immediate re-check of the exact same state.

        System.out.println(
                "-------------------------------------------------"
        );

        System.out.println(
                "[INFO] Starting camera stream validation."
        );

        for (int i = 0; i < cameraCount; i++) {

            Locator cameraCell =
                    cameraCells.nth(i);

            boolean result =
                    validateCameraStream(
                            cameraCell,
                            i + 1,
                            screenshotDir
                    );

            if (!result) {
                overallPassed = false;
            }
        }

        // ---------------------------------------------------------
        // FINAL RESULT
        // ---------------------------------------------------------

        System.out.println(
                "================================================="
        );

        if (overallPassed) {

            System.out.println(
                    "[PASS] ALL CAMERA STREAMS ARE AVAILABLE."
            );

        } else {

            System.err.println(
                    "[FAIL] ONE OR MORE CAMERA STREAMS FAILED."
            );

            System.err.println(
                    "[INFO] Screenshots stored at: "
                            + screenshotDir.toAbsolutePath()
            );
        }

        System.out.println(
                "================================================="
        );

        return overallPassed;

    } catch (Exception e) {

        System.err.println(
                "[LIVE VIEW ERROR] "
                        + e.getMessage()
        );

        e.printStackTrace();

        captureLiveViewScreenshot(
                screenshotDir,
                "live-view-exception"
        );

        return false;
    }
}

        private boolean validateCameraStream(Locator cameraCell, int cameraNumber, Path screenshotDir) {
                try {
                        Locator media = cameraCell.locator("video, canvas, [class*='video' i], [class*='player' i]").first();
                        if (media.count() == 0) {
                                captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-no-media");
                                return false;
                        }
                        // Fast path: the grid/panel above is already confirmed visible, so a healthy tile's
                        // media is normally visible immediately - skip the bounded wait entirely rather than
                        // always paying its cost. Only a genuinely slow/broken tile falls through to it, and
                        // even then it is capped well below the old 15s so one bad tile cannot dominate the
                        // whole camera loop.
                        if (!media.isVisible()) {
                                media.waitFor(new Locator.WaitForOptions()
                                                .setState(WaitForSelectorState.VISIBLE)
                                                .setTimeout(5000));
                        }
                        String tagName = media.evaluate("element => element.tagName.toLowerCase()").toString();
                        if ("video".equals(tagName)) {
                                String source = media.evaluate("element => element.currentSrc || element.src || ''").toString();
                                if (source.isBlank()) {
                                        captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-no-source");
                                        return false;
                                }
                                media.evaluate("element => { element.muted = true; element.play(); }");
                                // currentTime > 0 right after calling play() is racy - the element needs a beat
                                // to actually start decoding. Poll for it (condition-based, not a blind sleep)
                                // instead of checking once immediately or waiting a fixed amount.
                                boolean playing = pollUntilTrue(() -> {
                                        Object result = media.evaluate(
                                                        "element => !element.paused && !element.ended && element.currentTime > 0");
                                        return Boolean.TRUE.equals(result);
                                }, 2000);
                                if (!playing) {
                                        captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-not-playing");
                                        return false;
                                }
                        }
                        return true;
                } catch (Exception exception) {
                        captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-exception");
                        return false;
                }
        }

        /** Polls {@code condition} until true or {@code timeoutMs} elapses; bounded, never a blind sleep. */
        private boolean pollUntilTrue(java.util.function.BooleanSupplier condition, long timeoutMs) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (true) {
                        try {
                                if (condition.getAsBoolean()) {
                                        return true;
                                }
                        } catch (Exception ignored) {
                                // Transient evaluation error - treat as "not yet true" and keep polling.
                        }
                        if (System.currentTimeMillis() >= deadline) {
                                return false;
                        }
                        page.waitForTimeout(100);
                }
        }

        private void captureLiveViewScreenshot(Path screenshotDir, String name) {
                try {
                        Files.createDirectories(screenshotDir);
                        Path screenshotPath = screenshotDir.resolve(name + ".png");
                        page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath).setFullPage(true));
                        System.out.println("[SCREENSHOT] Live View failure saved to " + screenshotPath);
                } catch (Exception exception) {
                        System.err.println("[SCREENSHOT FAILURE] Could not save Live View screenshot: "
                                        + exception.getMessage());
                }
        }

}
