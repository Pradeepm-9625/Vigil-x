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

        if (!operatorPanel.isVisible()) {

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
        // 3. Wait for camera streams to load
        // ---------------------------------------------------------

        page.waitForTimeout(5000);

        // ---------------------------------------------------------
        // 4. Find camera containers
        // ---------------------------------------------------------

        Locator cameraCells =
                page.getByRole(AriaRole.GRIDCELL);

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
        // 5. First validation
        // ---------------------------------------------------------

        System.out.println(
                "-------------------------------------------------"
        );

        System.out.println(
                "[INFO] Starting initial camera validation."
        );

        for (int i = 0; i < cameraCount; i++) {

            Locator cameraCell =
                    cameraCells.nth(i);

            validateCameraStream(
                    cameraCell,
                    i + 1,
                    screenshotDir
            );
        }

        // ---------------------------------------------------------
        // 6. Wait 30 seconds
        // ---------------------------------------------------------

        System.out.println(
                "[INFO] Waiting 30 seconds before rechecking streams..."
        );

        page.waitForTimeout(30000);

        // ---------------------------------------------------------
        // 7. Second validation
        // ---------------------------------------------------------

        System.out.println(
                "-------------------------------------------------"
        );

        System.out.println(
                "[INFO] Starting second camera validation."
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
        // 8. Optional additional 30-second validation
        // ---------------------------------------------------------

        System.out.println(
                "[INFO] Waiting another 30 seconds..."
        );

        page.waitForTimeout(30000);

        System.out.println(
                "[INFO] Starting final camera validation."
        );

        // Recalculate because the DOM may have changed
        cameraCells =
                page.getByRole(AriaRole.GRIDCELL);

        cameraCount = cameraCells.count();

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
                        media.waitFor(new Locator.WaitForOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(15000));
                        String tagName = media.evaluate("element => element.tagName.toLowerCase()").toString();
                        if ("video".equals(tagName)) {
                                String source = media.evaluate("element => element.currentSrc || element.src || ''").toString();
                                if (source.isBlank()) {
                                        captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-no-source");
                                        return false;
                                }
                                media.evaluate("element => { element.muted = true; element.play(); }");
                                page.waitForTimeout(1000);
                                boolean playing = (Boolean) media.evaluate(
                                                "element => !element.paused && !element.ended && element.currentTime > 0");
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
