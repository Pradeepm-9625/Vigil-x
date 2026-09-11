package com.vigilx.pages;

import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.regex.Pattern;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

public class Map extends BasePage {

        public Map(Page page) {
                super(page);
        }

    public boolean validateMap(String baseUrl) {

    boolean overallPassed = true;

    // Screenshot location for soak test
    Path soakScreenshotDir = Paths.get(
            "target/soak-test/map"
    );

    try {

        Files.createDirectories(soakScreenshotDir);

        System.out.println("=================================================");
        System.out.println("MAP VALIDATION STARTED");
        System.out.println("=================================================");

        // ---------------------------------------------------------
        // 1. Open Map
        // ---------------------------------------------------------

        navigateTo(baseUrl + "/live-views/maps");

        System.out.println("[INFO] Map page opened.");

        // ---------------------------------------------------------
        // 2. Validate Map loaded
        // ---------------------------------------------------------

        Locator mapContainer = page.locator(
                ".leaflet-container"
        );

        if (mapContainer.count() == 0 ||
                !mapContainer.first().isVisible()) {

            System.err.println(
                    "[FAIL] Map container is not loaded."
            );

            captureMapScreenshot(
                    soakScreenshotDir,
                    "map-not-loaded"
            );

            return false;
        }

        System.out.println(
                "[PASS] Map container is loaded."
        );

        // Give map markers time to load
        page.waitForTimeout(3000);

        // ---------------------------------------------------------
        // 3. Find camera live buttons
        // ---------------------------------------------------------

        // Markers start clustered - five cameras render as one "5 Cameras" pin - and the
        // per-camera buttons exist only once the side panel is open and the clusters expanded.
        // Without this the count below is zero on a perfectly healthy map.
        MapValidation.revealMapDevices(page);

        Locator cameraButtons = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName(Pattern.compile("Open .* live.*", Pattern.CASE_INSENSITIVE))
        );

        int cameraCount = cameraButtons.count();

        System.out.println(
                "[INFO] Cameras available on map: "
                        + cameraCount
        );

        if (cameraCount == 0) {

            System.err.println(
                    "[FAIL] No camera live buttons found on map."
            );

            captureMapScreenshot(
                    soakScreenshotDir,
                    "no-map-cameras"
            );

            return false;
        }

        // ---------------------------------------------------------
        // 4. Random camera selection
        // ---------------------------------------------------------

        Random random = new Random();

        int randomIndex =
                random.nextInt(cameraCount);

        Locator selectedCamera =
                cameraButtons.nth(randomIndex);

        String cameraName =
                selectedCamera.getAttribute("aria-label");

        if (cameraName == null ||
                cameraName.isBlank()) {

            cameraName =
                    "Map-Camera-" +
                            (randomIndex + 1);
        }

        System.out.println(
                "[INFO] Random camera selected: "
                        + cameraName
        );

        // ---------------------------------------------------------
        // 5. Click random camera
        // ---------------------------------------------------------

        selectedCamera.scrollIntoViewIfNeeded();

        selectedCamera.click();

        System.out.println(
                "[INFO] Camera clicked: "
                        + cameraName
        );

        // ---------------------------------------------------------
        // 6. Find video (bounded wait for it to attach after the click;
        //    this is plumbing, not the 10s stream-establishment wait below)
        // ---------------------------------------------------------

        Locator videos =
                page.locator("video");

        try {
            videos.last().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(5000));
        } catch (Exception ignored) {
            // Falls through to the explicit count check below, which fails with a screenshot.
        }

        if (videos.count() == 0) {

            System.err.println(
                    "[FAIL] No video element found for "
                            + cameraName
            );

            captureMapScreenshot(
                    soakScreenshotDir,
                    "camera-" +
                            randomIndex +
                            "-video-not-found"
            );

            return false;
        }

        Locator video =
                videos.last();

        // ---------------------------------------------------------
        // 7. Check video visibility
        // ---------------------------------------------------------

        if (!video.isVisible()) {

            System.err.println(
                    "[FAIL] Video is not visible for "
                            + cameraName
            );

            captureMapScreenshot(
                    soakScreenshotDir,
                    "camera-" +
                            randomIndex +
                            "-video-not-visible"
            );

            return false;
        }

        // ---------------------------------------------------------
        // 8. Check video source
        // ---------------------------------------------------------

        String source =
                (String) video.evaluate(
                        """
                        element =>
                            element.currentSrc ||
                            element.src ||
                            ''
                        """
                );

        System.out.println(
                "[VIDEO SOURCE] "
                        + source
        );

        if (source == null ||
                source.isBlank()) {

            System.err.println(
                    "[FAIL] Video source is empty for "
                            + cameraName
            );

            captureMapScreenshot(
                    soakScreenshotDir,
                    "camera-" +
                            randomIndex +
                            "-no-video-source"
            );

            return false;
        }

        // ---------------------------------------------------------
        // 9. Check video error
        // ---------------------------------------------------------

        String videoError =
                (String) video.evaluate(
                        """
                        element => {
                            if (element.error) {
                                return JSON.stringify({
                                    code: element.error.code,
                                    message: element.error.message
                                });
                            }

                            return '';
                        }
                        """
                );

        if (videoError != null &&
                !videoError.isBlank()) {

            System.err.println(
                    "[FAIL] Video playback error for "
                            + cameraName
                            + ": "
                            + videoError
            );

            captureMapScreenshot(
                    soakScreenshotDir,
                    "camera-" +
                            randomIndex +
                            "-video-error"
            );

            return false;
        }

        // ---------------------------------------------------------
        // 10. Mute and start the stream
        // ---------------------------------------------------------

        video.evaluate(
                """
                element => {
                    element.muted = true;
                    element.volume = 0;
                    element.play();
                }
                """
        );

        // ---------------------------------------------------------
        // 11. Wait 10s for the live stream to establish itself
        // ---------------------------------------------------------

        System.out.println(
                "[INFO] Camera selected. Waiting 10s before validating stream availability..."
        );

        page.waitForTimeout(10000);

        // ---------------------------------------------------------
        // 12. Validate whether the stream is available
        // ---------------------------------------------------------

        int readyState =
                ((Number) video.evaluate(
                        "element => element.readyState"
                )).intValue();

        double startTime =
                ((Number) video.evaluate(
                        "element => element.currentTime"
                )).doubleValue();

        page.waitForTimeout(1000);

        double endTime =
                ((Number) video.evaluate(
                        "element => element.currentTime"
                )).doubleValue();

        boolean paused =
                (Boolean) video.evaluate(
                        "element => element.paused"
                );

        boolean ended =
                (Boolean) video.evaluate(
                        "element => element.ended"
                );

        int width =
                ((Number) video.evaluate(
                        "element => element.videoWidth"
                )).intValue();

        int height =
                ((Number) video.evaluate(
                        "element => element.videoHeight"
                )).intValue();

        boolean playbackProgressed =
                endTime > startTime + 0.05;

        boolean validResolution =
                width > 0 &&
                        height > 0;

        boolean streamAvailable =
                readyState >= 2 &&
                        !paused &&
                        !ended &&
                        playbackProgressed &&
                        validResolution;

        // ---------------------------------------------------------
        // 14. Log result
        // ---------------------------------------------------------

        System.out.println(
                "[MAP CAMERA VALIDATION]"
        );

        System.out.println(
                "Camera       : "
                        + cameraName
        );

        System.out.println(
                "Source       : "
                        + source
        );

        System.out.println(
                "Ready State  : "
                        + readyState
        );

        System.out.println(
                "Start Time   : "
                        + startTime
        );

        System.out.println(
                "End Time     : "
                        + endTime
        );

        System.out.println(
                "Paused       : "
                        + paused
        );

        System.out.println(
                "Resolution   : "
                        + width
                        + "x"
                        + height
        );

        // ---------------------------------------------------------
        // 15. PASS
        // ---------------------------------------------------------

        if (streamAvailable) {

            System.out.println(
                    "[PASS] Map camera stream is available: "
                            + cameraName
            );

            overallPassed = true;

        } else {

            // -----------------------------------------------------
            // FAIL
            // -----------------------------------------------------

            System.err.println(
                    "[FAIL] Map camera stream is NOT available: "
                            + cameraName
            );

            captureMapScreenshot(
                    soakScreenshotDir,
                    "camera-" +
                            randomIndex +
                            "-stream-failed"
            );

            overallPassed = false;
        }

        // ---------------------------------------------------------
        // 16. Close live preview
        // ---------------------------------------------------------

        closeMapLivePreview();

        return overallPassed;

    } catch (Exception e) {

        System.err.println(
                "[MAP VALIDATION ERROR] "
                        + e.getMessage()
        );

        e.printStackTrace();

        captureMapScreenshot(
                soakScreenshotDir,
                "map-validation-exception"
        );

        closeMapLivePreview();

        return false;
    }

        }

        private void captureMapScreenshot(Path screenshotDir, String name) {
                try {
                        Files.createDirectories(screenshotDir);
                        Path screenshotPath = screenshotDir.resolve(name + ".png");
                        page.screenshot(new Page.ScreenshotOptions()
                                        .setPath(screenshotPath)
                                        .setFullPage(true));
                        System.out.println("[SCREENSHOT] Map failure saved to " + screenshotPath);
                } catch (Exception exception) {
                        System.err.println("[SCREENSHOT FAILURE] Could not save Map screenshot: "
                                        + exception.getMessage());
                }
        }

        private void closeMapLivePreview() {
                page.keyboard().press("Escape");
        }
}
