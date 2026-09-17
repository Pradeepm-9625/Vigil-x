package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LiveViewCrudPage;
import com.vigilx.pages.LoginPage;

/** Throwaway probe (v2): creates a fresh, empty view first (matching runLiveViewCrudFlow's own
 * sequence), THEN opens the real Add Camera tree and lists actual camera names. */
public final class LiveViewTreeProbe2 {
    private LiveViewTreeProbe2() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));

            LiveViewCrudPage liveViewCrud = new LiveViewCrudPage(page);
            boolean navigated = liveViewCrud.navigateToLiveView();
            System.out.println("PROBE: navigated=" + navigated);

            String viewName = "TreeProbe " + System.currentTimeMillis();
            boolean created = liveViewCrud.createView(viewName);
            System.out.println("PROBE: created view '" + viewName + "'=" + created);

            boolean layoutOk = liveViewCrud.selectGridLayout("3x3");
            System.out.println("PROBE: layoutOk=" + layoutOk);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-lvtree2-1-empty-grid.png")).setFullPage(true));

            // Now open the Add Camera tree the same way LiveViewCrudPage's own openAddCameraTree()
            // does, on THIS fresh empty view.
            Locator gridAddCamera = page.getByRole(AriaRole.GRIDCELL).first()
                    .getByLabel("Add Camera", new Locator.GetByLabelOptions().setExact(false)).first();
            System.out.println("PROBE: gridAddCamera visible=" + (gridAddCamera.count() > 0 && gridAddCamera.isVisible()));
            if (gridAddCamera.count() > 0 && gridAddCamera.isVisible()) {
                gridAddCamera.click(new Locator.ClickOptions().setTimeout(10000));
                page.waitForTimeout(500);
            }
            Locator addCameraButton = page.locator("button").filter(new Locator.FilterOptions()
                    .setHasText(java.util.regex.Pattern.compile("add camera", java.util.regex.Pattern.CASE_INSENSITIVE))).first();
            System.out.println("PROBE: page-wide addCameraButton count=" + addCameraButton.count());
            if (addCameraButton.count() > 0) {
                addCameraButton.click(new Locator.ClickOptions().setTimeout(10000));
                page.waitForTimeout(800);
            }

            System.out.println("PROBE: treeitem count after opening panel=" + page.getByRole(AriaRole.TREEITEM).count());
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-lvtree2-2-tree-panel.png")).setFullPage(true));

            // Nodes here can carry a checkbox AND still be expandable (a site/branch node lets you
            // bulk-select all cameras under it, but its row click also expands it) - so "has a
            // checkbox" is NOT a reliable "this is a leaf camera" signal. Instead, repeatedly click
            // the SHORTEST not-yet-clicked treeitem text (a proxy for "most specific/nested so far")
            // by its row (never its checkbox), and stop once a full round adds no new treeitems.
            java.util.Set<String> clicked = new java.util.HashSet<>();
            int previousTotal = -1;
            for (int round = 0; round < 10; round++) {
                Locator items = page.getByRole(AriaRole.TREEITEM);
                int count = items.count();
                if (count == previousTotal && round > 0) {
                    System.out.println("PROBE: treeitem count stable at " + count + " after round " + round);
                    break;
                }
                previousTotal = count;
                String bestText = null;
                int bestIndex = -1;
                for (int i = 0; i < count; i++) {
                    Locator item = items.nth(i);
                    if (!item.isVisible()) continue;
                    String text = item.textContent();
                    if (text == null) continue;
                    text = text.trim();
                    if (clicked.contains(text)) continue;
                    if (bestText == null || text.length() < bestText.length()) {
                        bestText = text;
                        bestIndex = i;
                    }
                }
                if (bestIndex < 0) {
                    System.out.println("PROBE: no more unclicked nodes after round " + round);
                    break;
                }
                clicked.add(bestText);
                System.out.println("PROBE: round " + round + " clicking '" + bestText + "' (count=" + count + ")");
                try {
                    items.nth(bestIndex).click(new Locator.ClickOptions().setTimeout(5000));
                } catch (Exception e) {
                    System.out.println("PROBE:   click failed: " + e.getMessage());
                }
                page.waitForTimeout(600);
            }

            Locator allTreeItems = page.getByRole(AriaRole.TREEITEM);
            int total = allTreeItems.count();
            System.out.println("PROBE: total treeitem count after full expand=" + total);
            for (int i = 0; i < total; i++) {
                try {
                    Locator item = allTreeItems.nth(i);
                    if (!item.isVisible()) continue;
                    String text = item.textContent();
                    int checkboxCount = item.getByRole(AriaRole.CHECKBOX).count();
                    int nestedTreeItems = item.locator("[role='treeitem']").count();
                    boolean isLeaf = nestedTreeItems == 0;
                    System.out.println("PROBE:   item[" + i + "] leaf=" + isLeaf + " text='" + text
                            + "' hasCheckbox=" + (checkboxCount > 0));
                } catch (Exception ignored) { }
            }

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("target/probe-lvtree2-3-full-expand.png")).setFullPage(true));

            // Sanity check each leaf candidate under NVR2 resolves to exactly ONE unambiguous
            // treeitem by exact name (required for LiveViewCrudPage's own treeItem(name) lookup to
            // be safe).
            String[] candidates = {"NVR-001", "NVR-002", "Device 7001", "Device05", "Device LIC TEST2"};
            for (String candidate : candidates) {
                Locator exact = page.getByRole(AriaRole.TREEITEM,
                        new Page.GetByRoleOptions().setName(candidate).setExact(true));
                Locator loose = page.getByRole(AriaRole.TREEITEM,
                        new Page.GetByRoleOptions().setName(candidate).setExact(false));
                System.out.println("PROBE: treeitem count for '" + candidate + "' exact=" + exact.count()
                        + " loose=" + loose.count());
                if (loose.count() > 0) {
                    try {
                        System.out.println("PROBE:   loose[0] accessibleName='"
                                + loose.first().getAttribute("aria-label") + "'");
                    } catch (Exception ignored) { }
                }
            }

            // Cleanup: close without saving so this probe's own view doesn't linger like the
            // recorded reference's leftover "my view" did.
            Locator closeDialog = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Close dialog").setExact(false)).first();
            if (closeDialog.count() > 0 && closeDialog.isVisible()) {
                closeDialog.click(new Locator.ClickOptions().setTimeout(5000));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }
}
