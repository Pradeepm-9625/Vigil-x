package com.vigilx.tools;

import java.nio.file.Paths;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;

/** Throwaway diagnostic: explore Settings -> Organisation -> License -> Activate New License. */
public final class LicenseProbe {

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            page.waitForTimeout(1500);

            Locator openSettings = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
            if (openSettings.count() > 0 && openSettings.isVisible()) {
                openSettings.click();
                page.waitForTimeout(800);
            }
            page.getByText(Pattern.compile("organisation|organization", Pattern.CASE_INSENSITIVE)).first().click();
            page.waitForTimeout(1200);

            Locator licenseTab = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("License").setExact(false)).first();
            System.out.println("PROBE: License tab present=" + (licenseTab.count() > 0));
            licenseTab.click();
            page.waitForTimeout(1000);
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-license-1-tab.png")).setFullPage(true));

            System.out.println("PROBE: buttons visible on License tab:");
            page.getByRole(AriaRole.BUTTON).all().forEach(b -> {
                try { if (b.isVisible()) System.out.println("  BTN: [" + b.innerText().trim() + "]"); } catch (Exception ignored) { }
            });

            Locator activate = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Activate New License").setExact(false)).first();
            System.out.println("PROBE: 'Activate New License' present=" + (activate.count() > 0));
            if (activate.count() > 0) {
                // Watch for any API call fired by opening the dialog.
                page.onResponse(r -> System.out.println("PROBE: [RESP] " + r.request().method() + " " + r.url()
                        + " -> " + r.status()));
                activate.click();
                page.waitForTimeout(1200);
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-license-2-dialog.png")).setFullPage(true));

                System.out.println("PROBE: dialog-role count=" + page.getByRole(AriaRole.DIALOG).count());
                System.out.println("PROBE: 'Close dialog' buttons:");
                Locator closeButtons = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Close dialog").setExact(false));
                int n = closeButtons.count();
                System.out.println("  count=" + n);
                for (int i = 0; i < n; i++) {
                    Locator b = closeButtons.nth(i);
                    try {
                        System.out.println("  [" + i + "] visible=" + b.isVisible() + " aria-label=["
                                + b.getAttribute("aria-label") + "]");
                    } catch (Exception ignored) { }
                }

                System.out.println("PROBE: dialog text content:");
                page.getByRole(AriaRole.DIALOG).all().forEach(d -> {
                    try { System.out.println("  DIALOG TEXT: [" + d.innerText().trim().substring(0,
                            Math.min(300, d.innerText().trim().length())) + "]"); } catch (Exception ignored) { }
                });

                // Close via the last visible "Close dialog" (matches the recording's nth(1) on a
                // page with 2 total matches).
                Locator lastClose = closeButtons.last();
                if (lastClose.count() > 0 && lastClose.isVisible()) {
                    lastClose.click();
                    page.waitForTimeout(800);
                    System.out.println("PROBE: clicked last 'Close dialog'. dialog-role count now="
                            + page.getByRole(AriaRole.DIALOG).count());
                }
            }
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-license-3-after-close.png")).setFullPage(true));
        } catch (Exception exception) {
            System.out.println("PROBE ERROR: " + exception);
            exception.printStackTrace();
        } finally {
            page.waitForTimeout(1500);
            PlaywrightFactory.closeBrowser();
        }
    }
}
