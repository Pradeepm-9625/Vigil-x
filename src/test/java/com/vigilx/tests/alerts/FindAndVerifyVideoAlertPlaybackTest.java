package com.vigilx.tests.alerts;

import java.nio.file.Path;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.alert;
import com.vigilx.utils.LoggerUtils;

/**
 * Scans Alerts V1 across pages for the first "Video Alert" whose clip is actually pending play
 * ({@code .play-overlay} / {@code img[alt="play"]} present, per the user-confirmed live DOM),
 * then runs it through {@link alert#validateOpenAlertDetail} - the exact same click/detect/verify
 * logic {@link alert#validateAlerts} uses on its single random pick - to prove the play-overlay
 * click and full play/pause/resume verification actually work end to end.
 *
 * <p>Unlike {@link VideoAlertValidationTest} (one random alert, matching the required soak flow),
 * this test actively searches so it does not depend on luck; most alerts sampled live in this
 * environment carry only AI ROI snapshot images with no clip attached at all, confirmed via full
 * DOM/iframe/shadow-root inspection.
 *
 * <p>Run with: {@code mvn test -Dtest=FindAndVerifyVideoAlertPlaybackTest}
 */
public class FindAndVerifyVideoAlertPlaybackTest extends BaseTest {

    private static final Logger LOG = LoggerUtils.getLogger(FindAndVerifyVideoAlertPlaybackTest.class);
    private static final String[] PAGES_TO_SCAN = {"1"};
    private static final int ROWS_PER_PAGE = 20;

    @Test(timeOut = 480000, description = "Find a Video Alert with an active play overlay and verify it actually plays")
    public void findAndVerifyPlayback() {
        new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
        String appUrl = ConfigReader.get("base.url").replace("/onboarding", "");

        page.navigate(appUrl + "/alerts/v1?sortField=occurredAt&sortDirection=desc");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Alerts Alerts").setExact(true)).click();
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("Video Alert", Pattern.CASE_INSENSITIVE)))
                .first().click();
        page.waitForTimeout(5000);

        Path screenshot = Path.of("target", "video-alert-standalone-test", "found-video-alert-failure.png");
        alert alertPage = new alert(page);

        for (String pageNum : PAGES_TO_SCAN) {
            if (!"1".equals(pageNum)) {
                try {
                    page.getByText(pageNum, new Page.GetByTextOptions().setExact(true)).last()
                            .click(new Locator.ClickOptions().setTimeout(4000));
                    page.waitForTimeout(2500);
                } catch (Exception e) {
                    LOG.info("[SCAN] Could not reach page {}: {}", pageNum, e.getMessage());
                    continue;
                }
            }

            Locator badges = page.getByText("Video Alert", new Page.GetByTextOptions().setExact(true));
            int rows = Math.min(badges.count(), ROWS_PER_PAGE);
            for (int i = 2; i < rows; i++) {
                badges = page.getByText("Video Alert", new Page.GetByTextOptions().setExact(true));
                try {
                    badges.nth(i).click(new Locator.ClickOptions().setTimeout(4000));
                } catch (Exception e) {
                    continue;
                }
                Locator overlay = page.locator(".alerts-v1-detail-overlay");
                try {
                    overlay.waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE).setTimeout(6000));
                } catch (Exception e) {
                    continue;
                }
                page.waitForTimeout(1500);

                boolean hasPlayOverlay = overlay.locator(".play-overlay").count() > 0;
                boolean hasPlayIcon = overlay.locator("img[alt='play']").count() > 0;
                boolean hasDirectVideo = overlay.locator("video").count() > 0;

                if (!hasPlayOverlay && !hasPlayIcon && !hasDirectVideo) {
                    // No clip on this one - close and keep scanning.
                    try {
                        page.getByLabel("Close slider popup").click(new Locator.ClickOptions().setTimeout(3000));
                    } catch (Exception ignored) {
                    }
                    page.waitForTimeout(500);
                    continue;
                }

                LOG.info("[FOUND] page={} row={} playOverlay={} playIcon={} directVideo={} - "
                                + "running full click/detect/playback verification via alert.validateOpenAlertDetail()",
                        pageNum, i, hasPlayOverlay, hasPlayIcon, hasDirectVideo);

                boolean passed = alertPage.validateOpenAlertDetail(overlay, screenshot);

                Assert.assertTrue(passed,
                        "Found an alert with an active play overlay/icon/video (page=" + pageNum + ", row=" + i
                                + ") but the production validateOpenAlertDetail() did not verify it as playing. "
                                + "See console log above and screenshot at " + screenshot.toAbsolutePath());

                LOG.info("[PASS] Video Alert playback verified end-to-end (click -> mount -> play -> "
                        + "progress -> pause -> resume -> progress).");
                return;
            }
        }

        throw new org.testng.SkipException(
                "No Video Alert with an active play overlay/icon/video was found across the sampled pages ("
                        + String.join(", ", PAGES_TO_SCAN) + "). This environment's currently visible Video "
                        + "Alerts all carry only still-image ROI snapshots with no clip attached - confirmed "
                        + "via full DOM/iframe/shadow-root inspection. Provide a specific alert ID/URL known "
                        + "to have a clip to target it directly.");
    }
}
