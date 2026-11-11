package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.VmsOperatorSidebarPage;

/** Throwaway probe: opens the "Event Check" left-nav section and dumps its tabs/buttons/structure
 * to find the real "Search" + playback flow the user described (click an event thumbnail's play
 * control, confirm the recording actually plays vs "No recording available"). */
public final class EventCheckExploreProbe {
    private EventCheckExploreProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            String baseUrl = ConfigReader.get("base.url");
            page.navigate(baseUrl);
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            System.out.println("PROBE: login done");

            Locator profileMenu = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                    .setName(java.util.regex.Pattern.compile("quality check|admin|operator",
                            java.util.regex.Pattern.CASE_INSENSITIVE))).first();
            System.out.println("PROBE: profile menu visible = " + profileMenu.isVisible()
                    + " text=[" + profileMenu.innerText().replace("\n", " | ") + "]");
            profileMenu.click();
            page.waitForTimeout(600);
            Locator roleOptions = page.getByRole(AriaRole.MENUITEMRADIO);
            int roleCount = roleOptions.count();
            System.out.println("PROBE: role option count = " + roleCount);
            for (int i = 0; i < roleCount; i++) {
                System.out.println("PROBE: role[" + i + "] = [" + roleOptions.nth(i).innerText().trim() + "]");
            }
            for (int i = 0; i < roleCount; i++) {
                String text = roleOptions.nth(i).innerText().trim();
                if (text.toLowerCase().contains("operator")) {
                    roleOptions.nth(i).click();
                    System.out.println("PROBE: clicked role [" + text + "]");
                    break;
                }
            }
            page.waitForTimeout(800);

            // No role switch, no "Open settings" - look at whatever the default landing
            // nav/dashboard already offers, since "Event Check" may be a main left-nav item for
            // the default role rather than something tucked under Settings.
            Locator eventCheckDirect = page.getByText(java.util.regex.Pattern.compile(
                    "^\\s*Event Check\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE)).first();
            System.out.println("PROBE: 'Event Check' directly visible on landing page = "
                    + eventCheckDirect.isVisible());

            Locator allLinks = page.getByRole(AriaRole.LINK);
            int linkCount = allLinks.count();
            System.out.println("PROBE: role=link count = " + linkCount);
            for (int i = 0; i < linkCount; i++) {
                try {
                    String text = allLinks.nth(i).innerText().trim();
                    System.out.println("PROBE: link[" + i + "] = [" + text + "]");
                } catch (Exception ignored) {
                }
            }

            Locator sidebarImgs = page.locator("img[alt]");
            int imgCount = sidebarImgs.count();
            System.out.println("PROBE: img[alt] count = " + imgCount);
            for (int i = 0; i < Math.min(imgCount, 60); i++) {
                try {
                    System.out.println("PROBE: img[" + i + "] alt=[" + sidebarImgs.nth(i).getAttribute("alt") + "]");
                } catch (Exception ignored) {
                }
            }

            if (eventCheckDirect.isVisible()) {
                eventCheckDirect.click();
                page.waitForTimeout(1000);
            }

            Locator tabs = page.getByRole(AriaRole.TAB);
            int tabCount = tabs.count();
            System.out.println("PROBE: tab count = " + tabCount);
            for (int i = 0; i < tabCount; i++) {
                System.out.println("PROBE: tab[" + i + "] = [" + tabs.nth(i).innerText().trim() + "]");
            }

            Locator buttons = page.getByRole(AriaRole.BUTTON);
            int buttonCount = buttons.count();
            System.out.println("PROBE: button count = " + buttonCount);
            for (int i = 0; i < buttonCount; i++) {
                try {
                    String text = buttons.nth(i).innerText().trim();
                    if (!text.isBlank()) {
                        System.out.println("PROBE: button[" + i + "] = [" + text + "]");
                    }
                } catch (Exception ignored) {
                }
            }

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-event-check.png")).setFullPage(true));
            System.out.println("PROBE: screenshot saved to target/probe-event-check.png");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            page.waitForTimeout(2000);
            PlaywrightFactory.closeBrowser();
        }
    }
}
