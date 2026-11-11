package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.VmsOperatorSidebarPage;

/**
 * Throwaway probe: opens the "Add Bookmark" dialog's "Select Event" dropdown directly (bypassing
 * {@code LiveViewCrudPage.selectRandomEventType}) and dumps the option list's own outerHTML before
 * and after clicking one option, to find the REAL "this got selected" signal - the
 * "vxdd__item--selected" class guess did not reproduce it live. Never touches the rest of the
 * soak flow.
 */
public final class SnapshotDialogProbe {
    private SnapshotDialogProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            String baseUrl = ConfigReader.get("base.url");
            String appUrl = baseUrl.replace("/onboarding", "");
            page.navigate(baseUrl);
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            System.out.println("PROBE: login done");

            VmsOperatorSidebarPage vmsOperatorSidebar = new VmsOperatorSidebarPage(page);
            System.out.println("PROBE: switchRole(VMS Operator) = " + vmsOperatorSidebar.switchRole("VMS Operator"));

            page.navigate(appUrl + "/live-views/views/v1");
            page.waitForTimeout(3000);

            Locator bookmarkButton = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Bookmark").setExact(true)).first();
            bookmarkButton.click();
            page.waitForTimeout(500);

            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            System.out.println("PROBE: dialog visible = " + dialog.isVisible());

            Locator trigger = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Select Event").setExact(false)).first();
            System.out.println("PROBE: trigger text BEFORE open = [" + trigger.textContent() + "]");
            trigger.click();
            page.waitForTimeout(500);

            Locator menu = page.locator(".vxdd__menu").first();
            System.out.println("PROBE: menu visible = " + menu.isVisible());
            System.out.println("PROBE: menu outerHTML BEFORE click =\n" + menu.evaluate("e => e.outerHTML"));

            Locator items = menu.locator(".vxdd__item");
            int count = items.count();
            System.out.println("PROBE: item count = " + count);
            Locator target = null;
            for (int i = 0; i < count; i++) {
                String text = items.nth(i).innerText().trim();
                System.out.println("PROBE: item[" + i + "] = [" + text + "] class=["
                        + items.nth(i).getAttribute("class") + "]");
                if (!"none".equalsIgnoreCase(text)) {
                    target = items.nth(i);
                }
            }

            if (target != null) {
                String targetText = target.innerText().trim();
                System.out.println("PROBE: clicking item [" + targetText + "]");
                target.click();
                page.waitForTimeout(800);

                System.out.println("PROBE: menu still visible AFTER click = " + menu.isVisible());
                System.out.println("PROBE: dialog outerHTML AFTER click =\n" + dialog.evaluate("e => e.outerHTML"));
            } else {
                System.out.println("PROBE: no non-None item found to click.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            page.waitForTimeout(3000);
            PlaywrightFactory.closeBrowser();
        }
    }
}
