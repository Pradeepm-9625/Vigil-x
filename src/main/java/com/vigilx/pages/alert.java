package com.vigilx.pages;

import java.nio.file.Files;
import java.nio.file.Path;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/** Alerts V1 navigation plus video-playback validation for the selected Video Alert. */
public class alert extends BasePage {

    public alert(Page page) {
        super(page);
    }

    public boolean validateAlerts(String baseUrl, Path screenshotPath) {

    String testName = "Video Alert Validation";

    try {
        System.out.println("=================================================");
        System.out.println("STARTING: " + testName);
        System.out.println("=================================================");

        // Open Alerts
        navigateTo(baseUrl + "/alerts/v1?sortField=occurredAt&sortDirection=desc");

        System.out.println("[INFO] Navigated to Alerts page.");

        page.getByRole(
                AriaRole.LINK,
                new Page.GetByRoleOptions()
                        .setName("Alerts Alerts")
                        .setExact(true)
        ).click();

        System.out.println("[INFO] Alerts module opened.");

        // Select Video Alert
        page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Video Alert")
                        .setExact(false)
        ).first().click();

        System.out.println("[INFO] Video Alert selected.");

        // Open alert
        page.getByText(
                "Video Alert",
                new Page.GetByTextOptions()
                        .setExact(true)
        ).nth(2).click();

        System.out.println("[INFO] Video Alert details opened.");

        // Wait for alert detail
        Locator detailOverlay =
                page.locator(".alerts-v1-detail-overlay");

        detailOverlay.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(10000)
        );

        /*
         * IMPORTANT:
         * Do NOT click image preview / next image here.
         *
         * We first determine whether this alert actually contains
         * a video element.
         */

        Locator video =
                page.locator("video.unified-video-player__video");

        System.out.println("[INFO] Checking for video element...");

        // =========================================================
        // 1. VIDEO ELEMENT CHECK
        // =========================================================

        if (video.count() == 0) {

            System.err.println(
                    "[FAIL] VIDEO NOT AVAILABLE - No video element found."
            );

            System.err.println(
                    "[FAIL] Alert appears to contain image/preview only."
            );

            captureVideoFailureScreenshot(screenshotPath);

            return false;
        }

        System.out.println(
                "[INFO] Video element found. Count = " + video.count()
        );

        Locator videoElement = video.first();

        // =========================================================
        // 2. WAIT FOR VIDEO TO BECOME VISIBLE
        // =========================================================

        try {

            videoElement.waitFor(
                    new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(10000)
            );

        } catch (Exception e) {

            System.err.println(
                    "[FAIL] Video element exists but is not visible."
            );

            captureVideoFailureScreenshot(screenshotPath);

            return false;
        }

        // =========================================================
        // 3. CHECK VIDEO SOURCE
        // =========================================================

        String videoSource = (String) videoElement.evaluate(
                """
                element => element.currentSrc || element.src || ''
                """
        );

        System.out.println(
                "[VIDEO SOURCE] " + videoSource
        );

        if (videoSource == null || videoSource.isBlank()) {

            System.err.println(
                    "[FAIL] Video element exists but has no source."
            );

            captureVideoFailureScreenshot(screenshotPath);

            return false;
        }

        // =========================================================
        // 4. VIDEO ERROR CHECK
        // =========================================================

        String videoError = (String) videoElement.evaluate(
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

        if (videoError != null && !videoError.isBlank()) {

            System.err.println(
                    "[FAIL] Video has playback error: " + videoError
            );

            captureVideoFailureScreenshot(screenshotPath);

            return false;
        }

        // =========================================================
        // 5. MUTE VIDEO
        // =========================================================

        videoElement.evaluate(
                """
                element => {
                    element.muted = true;
                    element.volume = 0;
                }
                """
        );

        // =========================================================
        // 6. WAIT FOR VIDEO READY
        // =========================================================

        try {

            page.waitForFunction(
                    """
                    () => {
                        const video =
                            document.querySelector(
                                'video.unified-video-player__video'
                            );

                        return video &&
                               video.readyState >= 2;
                    }
                    """,
                    null,
                    new Page.WaitForFunctionOptions()
                            .setTimeout(15000)
            );

        } catch (Exception e) {

            System.err.println(
                    "[FAIL] Video source exists but video is not ready."
            );

            captureVideoFailureScreenshot(screenshotPath);

            return false;
        }

        // =========================================================
        // 7. START VIDEO
        // =========================================================

        System.out.println("[INFO] Starting video playback...");

        videoElement.evaluate(
                """
                element => {
                    element.play();
                }
                """
        );

        // Give browser time to start
        page.waitForTimeout(2000);

        // =========================================================
        // 8. CAPTURE START TIME
        // =========================================================

        double startTime = ((Number) videoElement.evaluate(
                "element => element.currentTime"
        )).doubleValue();

        boolean pausedAtStart = (Boolean) videoElement.evaluate(
                "element => element.paused"
        );

        int readyState = ((Number) videoElement.evaluate(
                "element => element.readyState"
        )).intValue();

        int videoWidth = ((Number) videoElement.evaluate(
                "element => element.videoWidth"
        )).intValue();

        int videoHeight = ((Number) videoElement.evaluate(
                "element => element.videoHeight"
        )).intValue();

        System.out.println("[VIDEO STATE]");
        System.out.println("Ready State : " + readyState);
        System.out.println("Paused      : " + pausedAtStart);
        System.out.println("CurrentTime : " + startTime);
        System.out.println("Video Size  : "
                + videoWidth + "x" + videoHeight);

        // =========================================================
        // 9. WAIT AND VERIFY TIME PROGRESS
        // =========================================================

        page.waitForTimeout(3000);

        double endTime = ((Number) videoElement.evaluate(
                "element => element.currentTime"
        )).doubleValue();

        boolean paused = (Boolean) videoElement.evaluate(
                "element => element.paused"
        );

        boolean ended = (Boolean) videoElement.evaluate(
                "element => element.ended"
        );

        System.out.println("[VIDEO PLAYBACK]");
        System.out.println("Start Time : " + startTime);
        System.out.println("End Time   : " + endTime);
        System.out.println("Paused     : " + paused);
        System.out.println("Ended      : " + ended);

        // =========================================================
        // 10. FINAL VALIDATION
        // =========================================================

        boolean timeProgressed =
                endTime > startTime + 0.2;

        boolean validDimensions =
                videoWidth > 0 &&
                videoHeight > 0;

        boolean videoIsReady =
                readyState >= 2;

        boolean videoAvailable =
                videoIsReady &&
                validDimensions &&
                timeProgressed &&
                !ended;

        // =========================================================
        // PASS
        // =========================================================

        if (videoAvailable) {

            System.out.println("=================================================");
            System.out.println("[PASS] VIDEO IS AVAILABLE AND PLAYING");
            System.out.println("=================================================");

            System.out.println(
                    "[PASS] Playback progressed from "
                            + startTime
                            + " to "
                            + endTime
            );

            try {
                page.getByLabel("Close slider popup").click();
            } catch (Exception ignored) {
            }

            return true;
        }

        // =========================================================
        // FAIL
        // =========================================================

        System.err.println("=================================================");
        System.err.println("[FAIL] VIDEO IS NOT AVAILABLE / NOT PLAYING");
        System.err.println("=================================================");

        System.err.println("Video Source : " + videoSource);
        System.err.println("Ready State  : " + readyState);
        System.err.println("Paused       : " + paused);
        System.err.println("Ended        : " + ended);
        System.err.println("Start Time   : " + startTime);
        System.err.println("End Time     : " + endTime);
        System.err.println(
                "Video Size   : "
                        + videoWidth
                        + "x"
                        + videoHeight
        );

        captureVideoFailureScreenshot(screenshotPath);

        return false;

    } catch (Exception exception) {

        System.err.println("=================================================");
        System.err.println("[VIDEO VALIDATION ERROR]");
        System.err.println(exception.getMessage());
        System.err.println("=================================================");

        exception.printStackTrace();

        captureVideoFailureScreenshot(screenshotPath);

        return false;

    } finally {
    try {
        Locator closeButton = page.getByLabel("Close slider popup");

        if (closeButton.count() > 0 && closeButton.isVisible()) {
            closeButton.click();
            System.out.println("[INFO] Video popup closed.");
        }
    } catch (Exception e) {
        System.out.println(
                "[INFO] Could not close video popup: "
                        + e.getMessage()
        );
    }
}
}

    private void captureVideoFailureScreenshot(Path screenshotPath) {
        try {
            Files.createDirectories(screenshotPath.getParent());
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));
            System.out.println("[SCREENSHOT] Video failure saved to " + screenshotPath);
        } catch (Exception exception) {
            System.err.println("[SCREENSHOT FAILURE] Could not save video failure screenshot: "
                    + exception.getMessage());
        }
    }

}
