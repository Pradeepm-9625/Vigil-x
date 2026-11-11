package com.vigilx.tools;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.LoginPage;

/** Throwaway probe: step-by-step verbose trace of selecting Country in the Add Node dialog, to
 * find out why the real selection silently does not register even though no exception is thrown. */
public final class CountryDropdownProbe {
    private CountryDropdownProbe() {
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

            Locator trigger = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Select country").setExact(true)).first();
            System.out.println("PROBE: trigger count=" + trigger.count() + " visible=" + trigger.isVisible());
            trigger.click(new Locator.ClickOptions().setTimeout(5000));
            page.waitForTimeout(600);

            Locator portal = page.locator(".vxdd__menu--portal").last();
            System.out.println("PROBE: portal count=" + portal.count() + " visible=" + portal.isVisible());
            System.out.println("PROBE: portal class=" + portal.getAttribute("class"));

            Locator search = portal.locator("input.vxdd__search-input").first();
            System.out.println("PROBE: search input count=" + search.count());
            if (search.count() > 0) {
                search.fill("indi");
                page.waitForTimeout(600);
            }

            Locator items = portal.locator("button.vxdd__item");
            int itemCount = items.count();
            System.out.println("PROBE: item count after filter='indi' = " + itemCount);
            for (int i = 0; i < itemCount; i++) {
                Locator item = items.nth(i);
                System.out.println("PROBE:   item[" + i + "] text='" + item.textContent().trim() + "' visible="
                        + item.isVisible());
            }

            Locator exactMatch = items.filter(new Locator.FilterOptions().setHasText(
                    Pattern.compile("^\\s*India\\s*$", Pattern.CASE_INSENSITIVE))).first();
            System.out.println("PROBE: exactMatch (^India$) count=" + exactMatch.count());
            if (exactMatch.count() > 0) {
                System.out.println("PROBE: exactMatch outerHTML=" + exactMatch.evaluate("el => el.outerHTML"));
                System.out.println("PROBE: exactMatch isVisible=" + exactMatch.isVisible()
                        + " isEnabled=" + exactMatch.isEnabled());
                exactMatch.click(new Locator.ClickOptions().setTimeout(5000));
                System.out.println("PROBE: clicked exactMatch, no exception thrown");
            } else {
                System.out.println("PROBE: no exact match - falling back to first().click()");
                items.first().click(new Locator.ClickOptions().setTimeout(5000));
            }
            page.waitForTimeout(1000);

            System.out.println("PROBE: portal still visible after click=" + portal.isVisible());
            Locator triggerAfter = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Select country").setExact(true)).first();
            System.out.println("PROBE: trigger (name='Select country' exact) STILL present after click="
                    + (triggerAfter.count() > 0));
            Locator countryContainerText = dialog.locator("*").filter(new Locator.FilterOptions()
                    .setHasText(Pattern.compile("^India$"))).first();
            System.out.println("PROBE: any dialog element with exact text 'India' now count="
                    + countryContainerText.count());

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-country-after-select.png")).setFullPage(false));

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }
}
