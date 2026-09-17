package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LiveViewCrudPage;
import com.vigilx.pages.LoginPage;

/** Throwaway probe: opens the Live View "My Views -> Cameras" Add Camera tree, expands the path
 * from the user's own recording (SCT PROJECT -> Sct n -branch campus -> SCT Site), and lists the
 * real camera names found underneath. */
public final class LiveViewTreeProbe {
    private LiveViewTreeProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));

            new LiveViewCrudPage(page).navigateToLiveView();
            page.waitForTimeout(100);

            // Open the Add Camera tree the same way LiveViewCrudPage does: an empty gridcell's own
            // "Add Camera" label, else a page-wide "Add Camera" button.
            Locator gridAddCamera = page.getByRole(AriaRole.GRIDCELL).first()
                    .getByLabel("Add Camera", new Locator.GetByLabelOptions().setExact(false)).first();
            if (gridAddCamera.count() > 0 && gridAddCamera.isVisible()) {
                gridAddCamera.click(new Locator.ClickOptions().setTimeout(10000));
                page.waitForTimeout(500);
            }
            Locator addCameraButton = page.locator("button").filter(new Locator.FilterOptions()
                    .setHasText(java.util.regex.Pattern.compile("add camera", java.util.regex.Pattern.CASE_INSENSITIVE))).first();
            if (addCameraButton.count() > 0) {
                addCameraButton.click(new Locator.ClickOptions().setTimeout(10000));
                page.waitForTimeout(800);
            }

            System.out.println("PROBE: treeitem count after opening panel=" + page.getByRole(AriaRole.TREEITEM).count());

            // Expand the path from the user's recording.
            for (String name : new String[] {"SCT PROJECT", "Sct n -branch campus", "SCT Site"}) {
                Locator node = page.getByRole(AriaRole.TREEITEM,
                        new Page.GetByRoleOptions().setName(name).setExact(false)).first();
                System.out.println("PROBE: node '" + name + "' count=" + node.count());
                if (node.count() > 0) {
                    node.click(new Locator.ClickOptions().setTimeout(10000));
                    page.waitForTimeout(800);
                }
            }

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-lvtree-1-expanded.png")).setFullPage(true));

            Locator allTreeItems = page.getByRole(AriaRole.TREEITEM);
            int total = allTreeItems.count();
            System.out.println("PROBE: total treeitem count=" + total);
            for (int i = 0; i < total; i++) {
                try {
                    Locator item = allTreeItems.nth(i);
                    if (!item.isVisible()) continue;
                    String text = item.textContent();
                    int checkboxCount = item.getByRole(AriaRole.CHECKBOX).count();
                    System.out.println("PROBE:   item[" + i + "] text='" + text + "' hasCheckbox=" + (checkboxCount > 0));
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }
}
