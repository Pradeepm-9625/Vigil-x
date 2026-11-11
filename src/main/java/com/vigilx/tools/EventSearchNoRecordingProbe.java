package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.VmsOperatorSidebarPage;

import java.nio.file.Paths;

/**
 * Throwaway probe: replays the user-recorded flow on the Archive "Event Search" tab exactly -
 * switch to VMS Operator, open Archive, open Search, click a timeline marker ({@code circle}) and
 * its SVG icon ({@code path} #3), then land on an event whose video never starts - to validate
 * whether the "No recording available" placeholder actually renders instead of a broken/blank
 * player. Read-only diagnostic: never touches {@link com.vigilx.pages.EventSearchValidation} or
 * any other existing page/flow.
 */
public final class EventSearchNoRecordingProbe {
    private EventSearchNoRecordingProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        boolean noRecordingShown = false;
        try {
            String baseUrl = ConfigReader.get("base.url");
            page.navigate(baseUrl);
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            System.out.println("PROBE: login done");

            VmsOperatorSidebarPage sidebar = new VmsOperatorSidebarPage(page);
            System.out.println("PROBE: switchRole(VMS Operator) = " + sidebar.switchRole("VMS Operator"));
            page.waitForTimeout(1000);

            Locator archiveButton = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Archive").setExact(true)).first();
            System.out.println("PROBE: 'Archive' button visible = " + archiveButton.isVisible());
            archiveButton.click();
            page.waitForTimeout(1500);

            Locator searchButton = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Search").setExact(true)).first();
            System.out.println("PROBE: 'Search' button visible = " + searchButton.isVisible());
            searchButton.click();
            page.waitForTimeout(1000);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("target/probe-event-search-before-circle.png")).setFullPage(true));
            System.out.println("PROBE: screenshot saved to target/probe-event-search-before-circle.png");

            Locator circle = page.locator("circle").first();
            int circleCount = page.locator("circle").count();
            System.out.println("PROBE: 'circle' element count = " + circleCount);
            if (circleCount == 0) {
                System.out.println("PROBE: RESULT = FAIL (no timeline 'circle' marker found on Event Search)");
                return;
            }
            System.out.println("PROBE: circle[0] box = " + circle.boundingBox());
            // Confirmed live: this circle is a play-button disc with its own white triangle <path>
            // drawn on top of it in the same SVG - a normal icon composition, not a bug - so a
            // plain click() sees the path "intercept" the circle's pointer events and refuses to
            // proceed. A real user's click in that spot still lands on the icon and fires its
            // handler, so force bypasses only that actionability mismatch, nothing else.
            try {
                circle.click(new Locator.ClickOptions().setTimeout(5000));
            } catch (Exception clickException) {
                System.out.println("PROBE: plain click on circle failed (" + clickException.getMessage()
                        + ") - retrying with force=true since an overlapping sibling <path> is expected");
                circle.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
            }
            page.waitForTimeout(800);

            Locator paths = page.locator("path");
            int pathCount = paths.count();
            System.out.println("PROBE: 'path' element count = " + pathCount);
            if (pathCount <= 3) {
                System.out.println("PROBE: RESULT = FAIL (fewer than 4 'path' elements - index [3] does not exist)");
                return;
            }
            Locator targetPath = paths.nth(3);
            System.out.println("PROBE: path[3] box = " + targetPath.boundingBox());
            try {
                targetPath.click(new Locator.ClickOptions().setTimeout(5000));
            } catch (Exception clickException) {
                System.out.println("PROBE: plain click on path[3] failed (" + clickException.getMessage()
                        + ") - retrying with force=true");
                targetPath.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
            }
            page.waitForTimeout(1500);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("target/probe-event-search-no-recording.png")).setFullPage(true));
            System.out.println("PROBE: screenshot saved to target/probe-event-search-no-recording.png");

            Locator noRecordingImage = page.getByRole(AriaRole.IMG,
                    new Page.GetByRoleOptions().setName("No recording available").setExact(false)).first();
            int noRecordingCount = noRecordingImage.count();
            System.out.println("PROBE: 'No recording available' image count = " + noRecordingCount);

            if (noRecordingCount == 0) {
                Locator anyVideo = page.locator("video").first();
                System.out.println("PROBE: no placeholder image found - checking for a playing <video> instead. "
                        + "video element present = " + (anyVideo.count() > 0));
                System.out.println("PROBE: RESULT = " + (anyVideo.count() > 0
                        ? "INCONCLUSIVE (a video rendered instead of the no-recording state - try a different marker)"
                        : "FAIL (neither the 'No recording available' placeholder nor a video rendered)"));
                return;
            }

            boolean visible = noRecordingImage.isVisible();
            System.out.println("PROBE: 'No recording available' image visible = " + visible);
            System.out.println("PROBE: image outerHTML = " + noRecordingImage.evaluate("e => e.outerHTML"));

            if (visible) {
                noRecordingImage.click();
                page.waitForTimeout(500);
                System.out.println("PROBE: clicked 'No recording available' image without error");
                noRecordingShown = true;
            }

            System.out.println("PROBE: RESULT = " + (noRecordingShown ? "PASS" : "FAIL")
                    + " (Event Search no-recording placeholder "
                    + (noRecordingShown ? "renders correctly" : "did not render visibly") + ")");
        } catch (Exception e) {
            System.out.println("PROBE: RESULT = FAIL (exception) - " + e.getMessage());
            e.printStackTrace();
        } finally {
            page.waitForTimeout(2000);
            PlaywrightFactory.closeBrowser();
        }
    }
}
