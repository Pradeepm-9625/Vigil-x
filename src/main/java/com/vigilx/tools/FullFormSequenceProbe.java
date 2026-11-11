package com.vigilx.tools;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.LoginPage;

/** Throwaway probe: replicates fillHierarchyForm's exact field order (Primary Contact, then
 * Country) with verbose tracing at each step, to find out why Country selection - which works
 * perfectly in isolation - silently fails to register once it runs right after Primary Contact. */
public final class FullFormSequenceProbe {
    private FullFormSequenceProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            new ApplicationHealthPage(page).validateProjectHierarchy();
            page.waitForTimeout(1000);

            Locator projectLink = page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Project Hierarchy Project").setExact(false)).first();
            if (projectLink.count() > 0 && projectLink.isVisible()) {
                projectLink.click();
            }
            page.waitForTimeout(500);
            page.locator(".MuiButtonBase-root").first().click();
            page.waitForTimeout(300);
            page.getByRole(AriaRole.MENUITEM,
                    new Page.GetByRoleOptions().setName("Add Node").setExact(false)).first().click();
            page.waitForTimeout(1000);

            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            System.out.println("PROBE: dialog count=" + dialog.count());

            // --- Step 1: Primary Contact (exactly like selectVxddOption(dialog, "Select primary contact", null, null)) ---
            select(page, dialog, "Select primary contact", null, null, "PRIMARY CONTACT");

            // --- Step 2: Country ---
            select(page, dialog, "Select country", "india", "India", "COUNTRY");

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-full-sequence.png")).setFullPage(false));

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }

    private static void select(Page page, Locator dialog, String triggerLabel, String filterText,
            String exactOptionText, String label) {
        System.out.println("PROBE[" + label + "]: starting");
        Locator trigger = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(triggerLabel).setExact(true)).first();
        boolean exactFound = waitVisible(trigger, 1500);
        System.out.println("PROBE[" + label + "]: exact trigger found=" + exactFound);
        if (!exactFound) {
            trigger = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(triggerLabel).setExact(false)).first();
        }
        boolean triggerFound = waitVisible(trigger, 3000);
        System.out.println("PROBE[" + label + "]: trigger found=" + triggerFound);
        if (!triggerFound) {
            return;
        }
        trigger.click(new Locator.ClickOptions().setTimeout(8000));
        System.out.println("PROBE[" + label + "]: trigger clicked");

        Locator portal = page.locator(".vxdd__menu--portal").last();
        boolean portalOpen = waitVisible(portal, 3000);
        System.out.println("PROBE[" + label + "]: portal open=" + portalOpen + " portal count="
                + page.locator(".vxdd__menu--portal").count() + " portal id="
                + (portal.count() > 0 ? portal.getAttribute("id") : "n/a"));

        if (filterText != null) {
            Locator search = portal.locator("input.vxdd__search-input").first();
            boolean searchFound = waitVisible(search, 2000);
            System.out.println("PROBE[" + label + "]: search box found=" + searchFound);
            if (searchFound) {
                search.fill(filterText);
                page.waitForTimeout(400);
            }
        }

        Locator items = portal.locator("button.vxdd__item");
        int itemCount = items.count();
        System.out.println("PROBE[" + label + "]: item count=" + itemCount);

        Locator target = items.first();
        if (exactOptionText != null) {
            Locator exactMatch = items.filter(new Locator.FilterOptions().setHasText(Pattern.compile(
                    "^\\s*" + Pattern.quote(exactOptionText) + "\\s*$", Pattern.CASE_INSENSITIVE))).first();
            System.out.println("PROBE[" + label + "]: exact match raw count=" + exactMatch.count());
            boolean matchVisible = waitVisible(exactMatch, 2000);
            System.out.println("PROBE[" + label + "]: exact match '" + exactOptionText
                    + "' visible (polled)=" + matchVisible);
            if (matchVisible) {
                target = exactMatch;
            } else {
                Locator looseMatch = items.filter(new Locator.FilterOptions().setHasText(
                        Pattern.compile(Pattern.quote(exactOptionText), Pattern.CASE_INSENSITIVE)));
                int looseCount = looseMatch.count();
                System.out.println("PROBE[" + label + "]: loose (contains) match count=" + looseCount);
                for (int i = 0; i < Math.min(looseCount, 5); i++) {
                    Locator li = looseMatch.nth(i);
                    System.out.println("PROBE[" + label + "]:   loose[" + i + "] outerHTML="
                            + li.evaluate("el => el.outerHTML") + " | textContent='" + li.textContent() + "'");
                }
                System.out.println("PROBE[" + label + "]: items[95..106] by position:");
                for (int i = 95; i <= 106 && i < itemCount; i++) {
                    Locator li = items.nth(i);
                    System.out.println("PROBE[" + label + "]:   pos[" + i + "] textContent='"
                            + li.textContent() + "' isVisible=" + li.isVisible());
                }
            }
        }

        boolean targetVisible = waitVisible(target, 3000);
        System.out.println("PROBE[" + label + "]: target visible=" + targetVisible);
        if (!targetVisible) {
            return;
        }
        try {
            target.click(new Locator.ClickOptions().setTimeout(8000));
            System.out.println("PROBE[" + label + "]: target clicked OK");
        } catch (Exception e) {
            System.out.println("PROBE[" + label + "]: target click THREW: " + e.getMessage());
        }
        page.waitForTimeout(500);

        Locator triggerAfter = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(triggerLabel).setExact(true)).first();
        System.out.println("PROBE[" + label + "]: trigger STILL shows original label after click="
                + (triggerAfter.count() > 0));
    }

    private static boolean waitVisible(Locator locator, int timeoutMs) {
        try {
            locator.waitFor(new Locator.WaitForOptions().setState(
                    com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
