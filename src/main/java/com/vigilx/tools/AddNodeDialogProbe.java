package com.vigilx.tools;

import java.nio.file.Paths;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.LoginPage;

/**
 * Throwaway probe: opens the real "Add Node" dialog's Primary Contact dropdown, and dumps
 * document.body's own top-level children (whichever one is the custom "vxdd" dropdown's portal
 * root) to find the real, role-less markup its option list renders with - role=menu/menuitem and
 * role=option all came back empty against the real app, so the previous guesses were wrong.
 */
public final class AddNodeDialogProbe {
    private AddNodeDialogProbe() {
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

            System.out.println("PROBE: document.body top-level children BEFORE opening any dropdown:");
            System.out.println(page.evaluate(
                    "() => Array.from(document.body.children).map((c,i) => i + ': <' + c.tagName + "
                            + "' class=\"' + c.className + '\" id=\"' + c.id + '\">').join('\\n')"));

            Locator primaryContact = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Select primary contact").setExact(false)).first();
            primaryContact.click(new Locator.ClickOptions().setTimeout(5000));
            page.waitForTimeout(1000);

            System.out.println("PROBE: document.body top-level children AFTER opening Primary Contact:");
            System.out.println(page.evaluate(
                    "() => Array.from(document.body.children).map((c,i) => i + ': <' + c.tagName + "
                            + "' class=\"' + c.className + '\" id=\"' + c.id + '\">').join('\\n')"));

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("target/probe-contact-open.png")).setFullPage(false));

            // Dump the last body child's full HTML (common portal convention: appended last) -
            // whichever one is NOT present in the BEFORE list is the real target; print every
            // body child's full outerHTML here so it can be diffed against the BEFORE run above.
            int bodyChildCount = ((Number) page.evaluate("() => document.body.children.length")).intValue();
            for (int i = 0; i < bodyChildCount; i++) {
                String html = (String) page.evaluate(
                        "(i) => { const el = document.body.children[i]; "
                                + "if (!el || el.id === 'root' || el.id === '__next') return '<skipped root app container>'; "
                                + "return el.outerHTML; }",
                        i);
                if (html != null && html.length() > 20 && !html.contains("skipped root app container")) {
                    System.out.println("PROBE: body.children[" + i + "] outerHTML (first 3000 chars)="
                            + truncate(html, 3000));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...<truncated>";
    }
}
