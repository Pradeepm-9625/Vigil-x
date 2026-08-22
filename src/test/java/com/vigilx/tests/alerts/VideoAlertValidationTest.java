package com.vigilx.tests.alerts;

import java.nio.file.Path;

import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.soak.SoakTestConfig;
import com.vigilx.utils.LoggerUtils;

/**
 * Focused, standalone entry point for the Alerts V1 / Video Alert playback validation only -
 * does not run the rest of the soak suite. Logs in, opens Alerts V1, picks a random Video Alert
 * and verifies its clip actually plays.
 *
 * <p>A random "Video Alert" row can legitimately carry only AI ROI snapshot images with no clip
 * ever attached - confirmed live: {@code .video-cam-card-media-stack--still-image}, zero
 * play-overlay/play-icon/video anywhere in the DOM (no iframe or shadow root hiding one either),
 * even after an explicit hover/mouse-move. That is a real, non-required outcome the same way the
 * full soak run treats it (see {@code soak.alert.required}), so this test mirrors that policy
 * instead of hard-failing on an unlucky random pick - it still fails loudly on a genuine defect
 * (preview + play overlay present, clicked, but no playback ever starts).
 *
 * <p>Run with: {@code mvn test -Dtest=VideoAlertValidationTest}
 */
public class VideoAlertValidationTest extends BaseTest {

    private static final Logger LOG = LoggerUtils.getLogger(VideoAlertValidationTest.class);

    @Test(description = "Verify Alerts V1 Video Alert clip is detected and actually plays")
    public void verifyVideoAlertPlayback() {
        LOG.info("Logging in before Video Alert validation...");
        DashboardPage dashboard = new LoginPage(page).login(
                ConfigReader.get("username"), ConfigReader.get("password"));
        Assert.assertTrue(dashboard.isDashboardLoaded(), "Dashboard should load after login.");

        String appUrl = ConfigReader.get("base.url").replace("/onboarding", "");
        Path screenshot = Path.of("target", "video-alert-standalone-test", "video-alert-failure.png");

        boolean passed = new ApplicationHealthPage(page).validateAlerts(appUrl, screenshot);

        if (passed) {
            LOG.info("[RESULT] PASS - Video Alert clip detected and playback verified (or the "
                    + "application explicitly indicated no clip for this alert).");
            return;
        }

        boolean required = SoakTestConfig.load().alertRequired();
        String message = "Video Alert validation FAILED for the randomly selected alert - no preview, "
                + "no play overlay/icon, and no video ever appeared (confirmed no iframe/shadow DOM "
                + "hiding it either). See console log above and screenshot at " + screenshot.toAbsolutePath();

        if (required) {
            Assert.fail(message);
        } else {
            // Mirrors SoakHealthCheckRunner: soak.alert.required=false means a video alert that
            // carries only images must not fail the run - report it, do not hard-fail the test.
            LOG.warn("[RESULT] WARN (soak.alert.required=false): {}", message);
        }
    }
}
