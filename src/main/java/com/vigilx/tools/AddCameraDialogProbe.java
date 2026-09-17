package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LiveViewCrudPage;
import com.vigilx.pages.LoginPage;

/** Throwaway probe: inspects the Add Camera dialog's own footer button(s) BEFORE and AFTER
 * checking a real camera's checkbox, to find the actual confirm control that commits the camera
 * to the grid tile (the earlier probe screenshot showed "Add Cameras", not "Save changes", before
 * any selection - need to see what it becomes/does after a real selection). */
public final class AddCameraDialogProbe {
    private AddCameraDialogProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));

            LiveViewCrudPage liveViewCrud = new LiveViewCrudPage(page);
            liveViewCrud.navigateToLiveView();
            String viewName = "DialogProbe " + System.currentTimeMillis();
            liveViewCrud.createView(viewName);
            liveViewCrud.selectGridLayout("3x3");

            // Open Add Camera tree manually (mirrors openAddCameraTree()).
            Locator gridAddCamera = page.getByRole(AriaRole.GRIDCELL).first()
                    .getByLabel("Add Camera", new Locator.GetByLabelOptions().setExact(false)).first();
            if (gridAddCamera.count() > 0 && gridAddCamera.isVisible()) {
                gridAddCamera.click(new Locator.ClickOptions().setTimeout(10000));
            }
            Locator addCameraButton = page.locator("button").filter(new Locator.FilterOptions()
                    .setHasText(java.util.regex.Pattern.compile("add camera", java.util.regex.Pattern.CASE_INSENSITIVE))).first();
            if (addCameraButton.count() > 0) {
                addCameraButton.click(new Locator.ClickOptions().setTimeout(10000));
            }
            page.waitForTimeout(500);

            printDialogButtons(page, "BEFORE any expansion");

            for (String name : new String[] {"SCT PROJECT", "Sct n -branch campus", "SCT Site", "NVR2"}) {
                Locator node = page.getByRole(AriaRole.TREEITEM,
                        new Page.GetByRoleOptions().setName(name).setExact(false)).first();
                if (node.count() > 0) {
                    node.click(new Locator.ClickOptions().setTimeout(5000));
                    page.waitForTimeout(500);
                }
            }
            printDialogButtons(page, "AFTER expanding to NVR2");

            Locator cameraItem = page.getByRole(AriaRole.TREEITEM,
                    new Page.GetByRoleOptions().setName("Device 7001").setExact(false)).first();
            Locator checkbox = cameraItem.getByRole(AriaRole.CHECKBOX).first();
            checkbox.check(new Locator.CheckOptions().setTimeout(10000));
            page.waitForTimeout(500);
            // The user's own recording ALSO clicks the treeitem row itself right after checking its
            // checkbox (not just the checkbox) - test whether that extra click is what actually
            // activates/commits the camera, since checkbox-only + panel-confirm left the tile empty.
            cameraItem.click(new Locator.ClickOptions().setTimeout(10000));
            page.waitForTimeout(500);

            printDialogButtons(page, "AFTER checking Device 7001");

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-dialog-after-check.png")).setFullPage(true));

            // Click the floating panel's own confirm button (whatever it is currently named), then
            // screenshot the grid state.
            Locator panel = page.locator(".operator-camera-floating-panel--add-camera").first();
            Locator confirm = panel.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(java.util.regex.Pattern.compile(
                            "add camera|save changes|save|confirm|apply", java.util.regex.Pattern.CASE_INSENSITIVE)))
                    .first();
            System.out.println("PROBE: confirm button count=" + confirm.count());
            if (confirm.count() > 0) {
                String confirmText = confirm.textContent();
                System.out.println("PROBE: clicking confirm button text='" + confirmText + "'");
                confirm.click(new Locator.ClickOptions().setTimeout(10000));
                page.waitForTimeout(1500);
            }

            System.out.println("PROBE: panel still open=" + (panel.count() > 0 && panel.isVisible()));
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-dialog-after-confirm.png")).setFullPage(true));

            // Now look at the outer toolbar for the actual save control.
            Locator allButtons = page.getByRole(AriaRole.BUTTON);
            int count = allButtons.count();
            System.out.println("PROBE: total visible page buttons=" + count);
            for (int i = 0; i < count; i++) {
                try {
                    Locator b = allButtons.nth(i);
                    if (b.isVisible()) {
                        System.out.println("PROBE:   button[" + i + "]='" + b.textContent().trim() + "'");
                    }
                } catch (Exception ignored) { }
            }

            // The accessible-name-based lookup for "Save" (exact) found nothing even though its
            // rendered text is "Save" - dump the raw attributes of the button whose text is "Save"
            // to find its real accessible name.
            Locator rawSaveByText = page.locator("button").filter(
                    new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile(
                            "^\\s*Save\\s*$"))).first();
            System.out.println("PROBE: rawSaveByText count=" + rawSaveByText.count());
            if (rawSaveByText.count() > 0) {
                System.out.println("PROBE:   aria-label=" + rawSaveByText.getAttribute("aria-label"));
                System.out.println("PROBE:   title=" + rawSaveByText.getAttribute("title"));
                System.out.println("PROBE:   class=" + rawSaveByText.getAttribute("class"));
                System.out.println("PROBE:   disabled=" + rawSaveByText.getAttribute("disabled"));
                System.out.println("PROBE:   outerHTML=" + rawSaveByText.evaluate("el => el.outerHTML"));
            }

            // Click the toolbar's own "Save" button (fall back to the raw text-matched locator,
            // since the accessible-name-based lookup found nothing) and see what happens next.
            Locator toolbarSave = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save").setExact(true)).first();
            System.out.println("PROBE: toolbarSave count=" + toolbarSave.count());
            if (toolbarSave.count() == 0) {
                toolbarSave = rawSaveByText;
            }
            if (toolbarSave.count() > 0) {
                toolbarSave.click(new Locator.ClickOptions().setTimeout(10000));
                page.waitForTimeout(1500);
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(java.nio.file.Paths.get("target/probe-after-toolbar-save.png")).setFullPage(true));

                Locator afterButtons = page.getByRole(AriaRole.BUTTON);
                int afterCount = afterButtons.count();
                System.out.println("PROBE: buttons after toolbar Save click=" + afterCount);
                for (int i = 0; i < afterCount; i++) {
                    try {
                        Locator b = afterButtons.nth(i);
                        if (b.isVisible()) {
                            System.out.println("PROBE:   afterButton[" + i + "]='" + b.textContent().trim() + "'");
                        }
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }

    private static void printDialogButtons(Page page, String label) {
        Locator dialog = page.locator(".operator-camera-floating-panel--add-camera").first();
        if (dialog.count() == 0) {
            System.out.println("PROBE[" + label + "]: no floating panel open");
            return;
        }
        Locator buttons = dialog.getByRole(AriaRole.BUTTON);
        int count = buttons.count();
        System.out.println("PROBE[" + label + "]: dialog button count=" + count);
        for (int i = 0; i < count; i++) {
            try {
                Locator b = buttons.nth(i);
                if (b.isVisible()) {
                    System.out.println("PROBE[" + label + "]:   button[" + i + "]='" + b.textContent().trim()
                            + "' disabled=" + Boolean.TRUE.equals(b.isDisabled()));
                }
            } catch (Exception ignored) { }
        }
    }
}
