package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.LoginPage;

/** Throwaway probe: runs the real create flow, and when it fails to save, inspects the Save
 * button's real actionability state (visible/enabled/bounding box/covering element) instead of
 * letting the exception get silently swallowed by clickAndWaitForResponse. */
public final class SaveClickProbe {
    private SaveClickProbe() {
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
            System.out.println("PROBE: dialog count=" + dialog.count() + " visible="
                    + (dialog.count() > 0 && dialog.isVisible()));

            Locator allDialogButtons = dialog.getByRole(AriaRole.BUTTON);
            int btnCount = allDialogButtons.count();
            System.out.println("PROBE: dialog button count=" + btnCount);
            for (int i = 0; i < btnCount; i++) {
                Locator b = allDialogButtons.nth(i);
                try {
                    System.out.println("PROBE:   button[" + i + "] text='" + b.textContent().trim()
                            + "' visible=" + b.isVisible() + " enabled=" + b.isEnabled());
                } catch (Exception ignored) {
                }
            }

            // Just fill the Node Name - minimum viable - then inspect Save directly without
            // touching any other field, to isolate whether Save itself is the problem.
            Locator nameField = dialog.getByRole(AriaRole.TEXTBOX,
                    new Locator.GetByRoleOptions().setName("Node Name").setExact(false)).first();
            System.out.println("PROBE: nameField count=" + nameField.count());
            nameField.fill("Probe Node " + System.currentTimeMillis());

            Locator saveButton = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Save").setExact(true)).first();
            System.out.println("PROBE: saveButton count=" + saveButton.count());
            System.out.println("PROBE: saveButton isVisible=" + saveButton.isVisible());
            System.out.println("PROBE: saveButton isEnabled=" + saveButton.isEnabled());
            System.out.println("PROBE: saveButton boundingBox=" + saveButton.boundingBox());

            // Scroll it into view explicitly first.
            saveButton.scrollIntoViewIfNeeded();
            page.waitForTimeout(300);
            System.out.println("PROBE: after scrollIntoView, boundingBox=" + saveButton.boundingBox());

            try {
                saveButton.click(new Locator.ClickOptions().setTimeout(8000));
                System.out.println("PROBE: saveButton.click() SUCCEEDED, no exception");
            } catch (Exception e) {
                System.out.println("PROBE: saveButton.click() THREW: " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
            }

            page.waitForTimeout(1500);
            System.out.println("PROBE: dialog still open=" + dialog.isVisible());

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-save-click.png")).setFullPage(false));

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }
}
