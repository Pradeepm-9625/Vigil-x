package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.VmsOperatorSidebarPage;

import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Throwaway probe: replays the user-recorded flow on the Archive "Bookmarks" tab exactly -
 * switch to VMS Operator, open Archive, open Search, switch to Bookmarks, filter a date range
 * (14 Oct - 2 Nov), Apply, then confirm a bookmark whose stream is unavailable correctly shows the
 * "No recording available" placeholder both in its list thumbnail AND in the preview panel once
 * selected, instead of erroring or rendering a broken player. Read-only diagnostic: never touches
 * {@link com.vigilx.pages.EventSearchValidation} or any other existing page/flow.
 */
public final class BookmarkNoRecordingProbe {
    private BookmarkNoRecordingProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        boolean listPlaceholderShown = false;
        boolean previewPlaceholderShown = false;
        try {
            String baseUrl = ConfigReader.get("base.url");
            page.navigate(baseUrl);
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            System.out.println("PROBE: login done");

            VmsOperatorSidebarPage sidebar = new VmsOperatorSidebarPage(page);
            System.out.println("PROBE: switchRole(VMS Operator) = " + sidebar.switchRole("VMS Operator"));
            page.waitForTimeout(1000);

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Archive").setExact(true))
                    .first().click();
            page.waitForTimeout(1500);

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Search").setExact(true))
                    .first().click();
            page.waitForTimeout(1000);

            Locator bookmarksTab = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("Bookmarks").setExact(true)).first();
            System.out.println("PROBE: 'Bookmarks' tab visible = " + bookmarksTab.isVisible());
            bookmarksTab.click();
            page.waitForTimeout(800);

            Locator dateButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(
                    Pattern.compile("Select|\\d{1,2}:\\d{2}\\s*(AM|PM)", Pattern.CASE_INSENSITIVE))).first();
            System.out.println("PROBE: date filter button visible = " + dateButton.isVisible()
                    + " text=[" + dateButton.textContent() + "]");
            dateButton.click();
            page.waitForTimeout(500);

            Locator octoberGrid = page.getByLabel("October");
            Locator octDay = octoberGrid.getByRole(AriaRole.GRIDCELL,
                    new Locator.GetByRoleOptions().setName("14").setExact(true)).first();
            System.out.println("PROBE: October day-14 gridcell visible = " + octDay.isVisible());
            octDay.click();
            page.waitForTimeout(300);

            Locator novemberGrid = page.getByLabel("November");
            Locator novDay = novemberGrid.getByRole(AriaRole.GRIDCELL,
                    new Locator.GetByRoleOptions().setName("2").setExact(true)).first();
            System.out.println("PROBE: November day-2 gridcell visible = " + novDay.isVisible());
            novDay.click();
            page.waitForTimeout(300);

            Locator applyButton = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Apply").setExact(false)).first();
            System.out.println("PROBE: 'Apply' enabled = " + applyButton.isEnabled());
            if (!applyButton.isEnabled()) {
                System.out.println("PROBE: RESULT = FAIL (date range 14 Oct - 2 Nov was not accepted)");
                return;
            }
            applyButton.click();
            page.waitForTimeout(1500);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("target/probe-bookmark-results.png")).setFullPage(true));
            System.out.println("PROBE: screenshot saved to target/probe-bookmark-results.png");

            Locator bookmarkCard = page.locator("label")
                    .filter(new Locator.FilterOptions().setHasText("CAM- SCT Office")).first();
            int cardCount = bookmarkCard.count();
            System.out.println("PROBE: 'CAM- SCT Office' bookmark card count = " + cardCount);
            if (cardCount == 0) {
                System.out.println("PROBE: RESULT = FAIL (expected bookmark not found in filtered results)");
                return;
            }

            Locator listPlaceholder = bookmarkCard.getByLabel("No recording available");
            int listPlaceholderCount = listPlaceholder.count();
            System.out.println("PROBE: list thumbnail 'No recording available' count = " + listPlaceholderCount);
            if (listPlaceholderCount > 0) {
                listPlaceholderShown = listPlaceholder.first().isVisible();
                System.out.println("PROBE: list thumbnail placeholder visible = " + listPlaceholderShown);
                listPlaceholder.first().click();
                page.waitForTimeout(1000);
            } else {
                System.out.println("PROBE: no 'No recording available' placeholder on the list thumbnail"
                        + " - this bookmark's stream may actually be available now.");
                bookmarkCard.click();
                page.waitForTimeout(1000);
            }

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("target/probe-bookmark-preview.png")).setFullPage(true));
            System.out.println("PROBE: screenshot saved to target/probe-bookmark-preview.png");

            // The right-hand preview panel is a separate region from the results grid, not
            // nested text under one shared "div" filter (confirmed live via screenshot: the
            // broad text-filter approach above matched the wrong/no div). Instead, every
            // "No recording available"-labelled element on the whole page is inspected, and
            // whichever one sits furthest right (the preview column, not a results-grid card)
            // is treated as the preview panel's own placeholder.
            Locator allPlaceholders = page.getByLabel("No recording available");
            int totalPlaceholders = allPlaceholders.count();
            System.out.println("PROBE: total 'No recording available' elements on page = " + totalPlaceholders);
            double viewportWidth = page.viewportSize() != null ? page.viewportSize().width : 1600;
            Locator previewPlaceholder = null;
            double rightmostX = -1;
            for (int i = 0; i < totalPlaceholders; i++) {
                Locator candidate = allPlaceholders.nth(i);
                try {
                    com.microsoft.playwright.options.BoundingBox box = candidate.boundingBox();
                    if (box != null) {
                        System.out.println("PROBE: placeholder[" + i + "] x=" + box.x + " y=" + box.y);
                        if (box.x > rightmostX) {
                            rightmostX = box.x;
                            previewPlaceholder = candidate;
                        }
                    }
                } catch (Exception ignored) {
                    // Keep checking the rest.
                }
            }
            if (previewPlaceholder != null && rightmostX > viewportWidth * 0.6) {
                previewPlaceholderShown = previewPlaceholder.isVisible();
                System.out.println("PROBE: preview panel placeholder visible = " + previewPlaceholderShown
                        + " (x=" + rightmostX + ", viewport width=" + viewportWidth + ")");
                previewPlaceholder.click();
                page.waitForTimeout(500);
            } else {
                System.out.println("PROBE: no 'No recording available' element found in the right-hand"
                        + " preview column (rightmost x=" + rightmostX + ", viewport width=" + viewportWidth + ")");
            }

            boolean pass = listPlaceholderShown && previewPlaceholderShown;
            System.out.println("PROBE: RESULT = " + (pass ? "PASS" : "FAIL")
                    + " (Bookmark 'No recording available' state - list=" + listPlaceholderShown
                    + ", preview=" + previewPlaceholderShown + ")");
        } catch (Exception e) {
            System.out.println("PROBE: RESULT = FAIL (exception) - " + e.getMessage());
            e.printStackTrace();
        } finally {
            page.waitForTimeout(2000);
            PlaywrightFactory.closeBrowser();
        }
    }
}
