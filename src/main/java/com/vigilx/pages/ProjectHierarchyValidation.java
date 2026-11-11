package com.vigilx.pages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.SoakUiUtils;

/**
 * Robust, soak-safe validation of the Project Hierarchy tree's own Create -&gt; Update -&gt; Delete
 * lifecycle: creates one brand-new, uniquely-named Node, renames it, creates one brand-new,
 * uniquely-named Site under that Node, renames the Site, then deletes the Site and the Node again -
 * in that exact order (Create Node -&gt; Update Node -&gt; Create Site -&gt; Update Site -&gt; Delete Site
 * -&gt; Delete Node), validating each step's background save/delete API.
 *
 * <p>Additional to {@link ApplicationHealthPage#validateProjectHierarchy}, which is left untouched
 * and is reused here (via {@link #open()}) instead of re-implementing the same navigation. The
 * dialog-closing, toast-reading and click-and-wait-for-API logic all come from {@link SoakUiUtils}
 * rather than being copied again here.
 *
 * <p>{@link #validateAuditLogs()} runs afterwards (unchanged by this class): opens the "Audit Logs"
 * tab, searches it and validates the background search API plus the rendered result (either the
 * {@code no-data-image} empty state or real rows - both valid), clears the search, then exercises
 * the export control and confirms a real file download starts.
 *
 * <p>Contract: never throws into the caller. Every failure is logged, screenshotted under
 * {@code target/soak-test/screenshots/project-hierarchy} and returned as {@code false} so the soak
 * continues.
 */
public class ProjectHierarchyValidation extends BasePage {

    private static final String SEP = "============================================================";
    private static final String DEFAULT_SCREENSHOT_DIRECTORY = "target/soak-test/screenshots/project-hierarchy";
    private static final int ELEMENT_TIMEOUT_MS = 8000;

    private final Path screenshotDirectory;

    public ProjectHierarchyValidation(Page page) {
        super(page);
        this.screenshotDirectory = Paths.get(ConfigReader.getOrDefault(
                "project.hierarchy.screenshot.directory", DEFAULT_SCREENSHOT_DIRECTORY));
    }

    // ---------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------

    /**
     * Opens Project Hierarchy. No duplicate navigation: reuses the page when the existing
     * "Project Hierarchy" check already left it open, otherwise delegates to
     * {@link ApplicationHealthPage#validateProjectHierarchy()} rather than re-implementing the same
     * navigate-and-wait.
     */
    public boolean open() {
        if (isOnProjectHierarchyPage()) {
            System.out.println("[PROJECT HIERARCHY] Already on Project Hierarchy; reusing it.");
            return true;
        }
        return new ApplicationHealthPage(page).validateProjectHierarchy();
    }

    private boolean isOnProjectHierarchyPage() {
        return SoakUiUtils.isVisibleQuietly(page.getByPlaceholder("Search Node, Site, Devices").first())
                || SoakUiUtils.isVisibleQuietly(page.locator(
                        "[id*='mui-tree-view'], .MuiTreeView-root, .ph-v1-dynamic-tree-node").first());
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Runs the full Node/Site lifecycle in the exact required order: Create Node -&gt; Update Node
     * -&gt; Create Site -&gt; Update Site -&gt; Delete Site -&gt; Delete Node. The Site is created
     * under the newly created Node (not an arbitrary existing one), and both names get a per-run
     * 6-digit timestamp suffix (same convention as user/group/role creation) so repeated runs never
     * collide. Returns {@code true} only when every one of the six steps was confirmed by its own
     * background API (or, when no matching API response is observed, a clear success toast/dialog
     * close - same fallback the rest of this module already uses).
     */
    public boolean validateRandomNodeAndSite() {
        System.out.println(SEP);
        System.out.println("PROJECT HIERARCHY VALIDATION - Node/Site Create -> Update -> Delete lifecycle");
        System.out.println(SEP);

        String suffix = String.format("%06d", (System.currentTimeMillis() / 1000) % 1_000_000);
        String nodeName = "Soak Node " + suffix;
        String nodeUpdatedName = nodeName + " update";
        String siteName = "Soak Site " + suffix;
        String siteUpdatedName = siteName + " update";

        boolean nodeCreated = createHierarchyItem("Node", nodeName, null);
        boolean nodeUpdated = nodeCreated && renameHierarchyItem("Node", nodeName, nodeUpdatedName);
        boolean siteCreated = nodeUpdated && createHierarchyItem("Site", siteName, nodeUpdatedName);
        boolean siteUpdated = siteCreated && renameHierarchyItem("Site", siteName, siteUpdatedName);
        // Delete Site before Delete Node regardless of whether the steps above all passed - a Site
        // actually created under this run's Node must never be left behind just because, say, its
        // own rename failed; it still exists and still needs cleaning up before the Node it lives
        // under can be deleted. Only skipped when Create Site itself never ran/passed (there is then
        // nothing to delete).
        boolean siteDeleted = !siteCreated
                || deleteHierarchyItem("Site", siteUpdated ? siteUpdatedName : siteName);
        boolean nodeDeleted = !nodeCreated
                || deleteHierarchyItem("Node", nodeUpdated ? nodeUpdatedName : nodeName);

        System.out.println(SEP);
        System.out.println("PROJECT HIERARCHY RESULT");
        System.out.println("Create Node : " + (nodeCreated ? "PASS" : "FAIL") + " (" + nodeName + ")");
        System.out.println("Update Node : " + (nodeUpdated ? "PASS" : "FAIL"));
        System.out.println("Create Site : " + (siteCreated ? "PASS" : "FAIL") + " (" + siteName + ")");
        System.out.println("Update Site : " + (siteUpdated ? "PASS" : "FAIL"));
        System.out.println("Delete Site : " + (siteDeleted ? "PASS" : "FAIL"));
        System.out.println("Delete Node : " + (nodeDeleted ? "PASS" : "FAIL"));
        System.out.println(SEP);

        return nodeCreated && nodeUpdated && siteCreated && siteUpdated && siteDeleted && nodeDeleted;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    /**
     * Opens "Add {@code kind}" - from the Project root's own actions menu for a Node, or from the
     * parent Node's own actions menu (named {@code parentNodeName}) for a Site - fills the dialog
     * with disposable test data (none of it asserted on; only that Save succeeds matters here) and
     * confirms via the background create API.
     */
    private boolean createHierarchyItem(String kind, String name, String parentNodeName) {
        Locator menuButton;
        if (parentNodeName == null) {
            Locator projectLink = page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Project Hierarchy Project").setExact(false)).first();
            if (SoakUiUtils.waitVisible(projectLink, ELEMENT_TIMEOUT_MS)) {
                try {
                    projectLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                } catch (Exception ignored) {
                    // The root menu button below is still reachable even if this breadcrumb click misses.
                }
            }
            menuButton = page.locator(".MuiButtonBase-root").first();
        } else {
            Locator parentItem = findTreeItemByName(parentNodeName);
            if (!SoakUiUtils.waitVisible(parentItem, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[PROJECT HIERARCHY]   Parent Node '" + parentNodeName
                        + "' not found; cannot add " + kind + " '" + name + "'.");
                return false;
            }
            parentItem.scrollIntoViewIfNeeded();
            menuButton = parentItem.getByLabel("Open actions menu").first();
        }

        if (!clickMenuButtonAndItem(menuButton, "Add " + kind)) {
            System.err.println("[PROJECT HIERARCHY]   'Add " + kind + "' could not be opened for '" + name + "'.");
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            capture("create-" + SoakUiUtils.slug(kind) + "-dialog-not-open");
            System.err.println("[PROJECT HIERARCHY]   Add " + kind + " dialog did not open for '" + name + "'.");
            return false;
        }

        fillHierarchyForm(dialog, kind, name);

        // Confirmed live: this dialog's own footer button reads "Save", never "Save changes" -
        // but exact=true still finds nothing, because (like Primary Contact's trigger) it nests an
        // icon whose alt text gets folded into the real accessible name, making it something other
        // than the literal visible text "Save" - exact=false (substring) is required here too.
        Locator saveButton = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Save").setExact(false)).first();
        page.waitForTimeout(800);
        boolean passed = saveAndVerify(dialog, saveButton, "create " + kind, name);
        SoakUiUtils.dismissDialog(page);
        return passed;
    }

    /**
     * Fills every field the Add/Rename {@code kind} dialog shares in common - Name, (Site only)
     * Site Type, primary contact, Note, Country, State, City, Zip, Address, Timezone, and a
     * best-effort map location - all disposable filler data, never verified afterwards (only that
     * Save succeeds is checked by the caller).
     */
    private void fillHierarchyForm(Locator dialog, String kind, String name) {
        Locator nameField = dialog.getByRole(AriaRole.TEXTBOX,
                new Locator.GetByRoleOptions().setName(kind + " Name").setExact(false)).first();
        try {
            nameField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            nameField.fill(name);
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY]   '" + kind + " Name' could not be filled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }

        if ("Site".equalsIgnoreCase(kind)) {
            selectVxddOption(dialog, "Select site type", null, "Station");
        }

        // Confirmed live: this environment's real contact list has no "Dont delete" entry (that
        // was specific to a different environment's seed data) - picking whichever contact is
        // first in the live list is the only name-agnostic way to satisfy this required field
        // across environments, and which one is chosen is never asserted on afterwards.
        selectVxddOption(dialog, "Select primary contact", null, null);

        Locator noteField = dialog.getByRole(AriaRole.TEXTBOX,
                new Locator.GetByRoleOptions().setName("Note").setExact(false)).first();
        fillBestEffort(noteField, "test");

        selectVxddOption(dialog, "Select country", "india", "India");
        // State's trigger stays disabled ("Select country first") until Country's own choice
        // finishes propagating (its state list depends on the chosen country) - a short settle
        // delay here, not needed by Country/Primary Contact themselves, avoids querying for
        // "Select state" before the button has actually switched over to it.
        page.waitForTimeout(800);
        selectVxddOption(dialog, "Select state", "tamil", "Tamil Nadu");

        fillBestEffort(dialog.getByRole(AriaRole.TEXTBOX,
                new Locator.GetByRoleOptions().setName("City").setExact(false)).first(), "Coimbatore");
        fillBestEffort(dialog.getByRole(AriaRole.TEXTBOX,
                new Locator.GetByRoleOptions().setName("Zip/Pin Code").setExact(false)).first(), "641045");
        fillBestEffort(dialog.getByRole(AriaRole.TEXTBOX,
                new Locator.GetByRoleOptions().setName("Address").setExact(false)).first(), "test street");

        selectVxddOption(dialog, "Select timezone", "kolkata", "Asia/Kolkata");

        bestEffortEditLocation(dialog);
    }

    /**
     * Opens one of this app's own "vxdd" dropdowns ({@code triggerLabel}, e.g. "Select country")
     * and picks an option from it. Confirmed live against the real markup: these dropdowns render
     * their option list in a {@code .vxdd__menu--portal} panel appended as the LAST child of
     * {@code <body>} - a sibling of the dialog, never inside it - and each option is a real
     * {@code <button class="vxdd__item">}, not a role=listbox/option/menuitem element (all come
     * back empty against the real app) and not a plain-text row either.
     *
     * <p>When {@code filterText} is given, it is typed into the panel's own search input (matched
     * by its {@code vxdd__search-input} class, not by guessing an aria-label - this app's real
     * search inputs are labelled "Search {field}", never "Select {field}") before picking, since
     * some of these lists (Country, State, Timezone) are far too long to pick from unfiltered.
     *
     * <p>When {@code exactOptionText} is given, the option whose own text equals it (case
     * -insensitive) is preferred; otherwise - or if no such option exists in this environment's
     * live data - the first available option is clicked instead, so a required field (Primary
     * Contact) is never left empty just because a specific name does not exist here.
     */
    private void selectVxddOption(Locator dialog, String triggerLabel, String filterText, String exactOptionText) {
        // Exact match first (needed for Country, whose trigger prefix-collides with State's own
        // disabled "Select country first" placeholder before Country is chosen) - falling back to
        // a substring match for triggers whose accessible name picks up extra text Playwright
        // includes but a human never reads, e.g. Primary Contact's trigger nests an icon with
        // alt="chevron down", making its real accessible name "Select primary contact chevron
        // down" - confirmed live, an exact match on just the visible label never found it.
        Locator trigger = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(triggerLabel).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(trigger, 1500)) {
            trigger = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(triggerLabel).setExact(false)).first();
        }
        if (!SoakUiUtils.waitVisible(trigger, 3000)) {
            System.out.println("[PROJECT HIERARCHY]   '" + triggerLabel + "' trigger not present; skipping.");
            return;
        }
        try {
            trigger.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY]   '" + triggerLabel + "' could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return;
        }

        Locator portal = page.locator(".vxdd__menu--portal").last();
        if (!SoakUiUtils.waitVisible(portal, 3000)) {
            System.err.println("[PROJECT HIERARCHY]   '" + triggerLabel + "' dropdown did not open.");
            return;
        }

        try {
            if (filterText != null) {
                Locator search = portal.locator("input.vxdd__search-input").first();
                if (SoakUiUtils.waitVisible(search, 2000)) {
                    search.fill(filterText);
                    page.waitForTimeout(400);
                }
            }

            Locator items = portal.locator("button.vxdd__item");
            Locator target = items.first();
            if (exactOptionText != null) {
                Locator exactMatch = items.filter(new Locator.FilterOptions().setHasText(Pattern.compile(
                        "^\\s*" + escapeRegex(exactOptionText) + "\\s*$", Pattern.CASE_INSENSITIVE))).first();
                // Confirmed live: a long list (e.g. Country's ~250 entries) can still be fading/
                // rendering in for a moment right after the portal itself reports visible - a single
                // isVisibleQuietly() snapshot taken at that instant catches it mid-animation and
                // reports not-visible even though the match is really there, silently falling back
                // to the list's first entry ("None") instead of the real target. Polling (not a
                // one-shot check) gives it the moment it needs to settle.
                if (SoakUiUtils.waitVisible(exactMatch, 2000)) {
                    target = exactMatch;
                }
            }

            if (!SoakUiUtils.waitVisible(target, 3000)) {
                System.err.println("[PROJECT HIERARCHY]   '" + triggerLabel + "' had no option to select.");
                closeMenu();
                return;
            }
            target.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY]   '" + triggerLabel + "' option could not be selected: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
    }

    /**
     * Best-effort only: opens the location sub-dialog and saves it - never fails the caller on its
     * own, since whether a precise location was set is not what this flow validates (Save's own API
     * outcome, checked separately, is the real gate). Deliberately just "Edit location" then "Save",
     * exactly as verified live - the previous version also clicked a "Location pin" button and a
     * {@code .location-map__map} element in between, neither of which matched anything real, which
     * left the sub-dialog in a state the final Save click couldn't get past.
     */
    private void bestEffortEditLocation(Locator dialog) {
        try {
            Locator editLocation = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Edit location").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(editLocation, 3000)) {
                return;
            }
            editLocation.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            // .last(), not .first(): the Add Node dialog itself also has its own "Save" button, and
            // this location sub-dialog - opened on top of it - is appended after it in the DOM, so
            // .first() risks clicking the wrong (outer, currently covered) one. exact=false: same
            // icon-in-accessible-name issue as the other triggers/buttons in this file.
            Locator saveLocation = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save").setExact(false)).last();
            if (SoakUiUtils.waitVisible(saveLocation, 3000)) {
                saveLocation.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
        } catch (Exception exception) {
            System.out.println("[PROJECT HIERARCHY]   Edit location skipped (best-effort): "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
    }

    private void fillBestEffort(Locator field, String value) {
        try {
            if (SoakUiUtils.waitVisible(field, 3000)) {
                field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                field.fill(value);
            }
        } catch (Exception exception) {
            System.out.println("[PROJECT HIERARCHY]   Field fill skipped (best-effort): "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    // Update (rename)
    // ---------------------------------------------------------------------

    /** Opens {@code currentName}'s own "Rename {@code kind}" action, fills the new name, Save changes. */
    private boolean renameHierarchyItem(String kind, String currentName, String newName) {
        Locator item = findTreeItemByName(currentName);
        if (!SoakUiUtils.waitVisible(item, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[PROJECT HIERARCHY]   '" + currentName + "' not found; cannot rename.");
            return false;
        }
        item.scrollIntoViewIfNeeded();
        Locator menuButton = item.getByLabel("Open actions menu").first();
        if (!clickMenuButtonAndItem(menuButton, "Rename " + kind)) {
            System.err.println("[PROJECT HIERARCHY]   'Rename " + kind + "' could not be opened for '"
                    + currentName + "'.");
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            capture("update-" + SoakUiUtils.slug(kind) + "-dialog-not-open");
            System.err.println("[PROJECT HIERARCHY]   Rename " + kind + " dialog did not open for '"
                    + currentName + "'.");
            return false;
        }

        Locator nameField = dialog.getByRole(AriaRole.TEXTBOX,
                new Locator.GetByRoleOptions().setName(kind + " Name").setExact(false)).first();
        try {
            nameField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            nameField.fill(newName);
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY]   '" + kind + " Name' could not be updated: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }

        Locator saveButton = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Save").setExact(false)).first();
        page.waitForTimeout(800);
        boolean passed = saveAndVerify(dialog, saveButton, "update " + kind, newName);
        SoakUiUtils.dismissDialog(page);
        return passed;
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /** Opens {@code name}'s own "Delete {@code kind}" action and confirms it. */
    private boolean deleteHierarchyItem(String kind, String name) {
        Locator item = findTreeItemByName(name);
        if (!SoakUiUtils.waitVisible(item, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[PROJECT HIERARCHY]   '" + name + "' not found; cannot delete.");
            return false;
        }
        item.scrollIntoViewIfNeeded();
        Locator menuButton = item.getByLabel("Open actions menu").first();
        if (!clickMenuButtonAndItem(menuButton, "Delete " + kind)) {
            System.err.println("[PROJECT HIERARCHY]   'Delete " + kind + "' could not be opened for '" + name + "'.");
            return false;
        }

        Locator confirmButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Delete").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(confirmButton, ELEMENT_TIMEOUT_MS)) {
            capture("delete-" + SoakUiUtils.slug(kind) + "-confirm-not-open");
            System.err.println("[PROJECT HIERARCHY]   Delete " + kind + " confirmation did not open for '"
                    + name + "'.");
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(
                page, confirmButton, this::isProjectHierarchyWriteResponse, 15000);
        String toast = waitForToast(toastBefore, 6000);

        if (response != null) {
            int status = response.status();
            boolean pass = status >= 200 && status < 300;
            System.out.println("[PROJECT HIERARCHY]   Delete " + kind + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + status + " (" + (pass ? "PASS" : "FAIL") + ")"
                    + (toast.isBlank() ? "" : " | toast: \"" + toast + "\""));
            if (!pass) {
                String shot = capture("delete-" + SoakUiUtils.slug(kind) + "-api-failed");
                System.err.println("[PROJECT HIERARCHY]   Delete " + kind + " FAILED for '" + name
                        + "' (HTTP " + status + "). Screenshot: " + (shot == null ? "<not captured>" : shot));
            }
            return pass;
        }

        boolean rowGone = !SoakUiUtils.isVisibleQuietly(findTreeItemByName(name).first());
        boolean badToast = !toast.isBlank()
                && Pattern.compile("fail|error|unable|could not", Pattern.CASE_INSENSITIVE).matcher(toast).find();
        if (badToast) {
            String shot = capture("delete-" + SoakUiUtils.slug(kind) + "-failed");
            System.err.println("[PROJECT HIERARCHY]   Delete " + kind + " FAILED for '" + name
                    + "' (toast: \"" + toast + "\"). Screenshot: " + (shot == null ? "<not captured>" : shot));
            return false;
        }
        if (rowGone) {
            System.out.println("[PROJECT HIERARCHY]   Delete " + kind + " confirmed (row '" + name
                    + "' no longer present)" + (toast.isBlank() ? "" : " toast: \"" + toast + "\""));
            return true;
        }

        String shot = capture("delete-" + SoakUiUtils.slug(kind) + "-unconfirmed");
        System.err.println("[PROJECT HIERARCHY]   Delete " + kind + " for '" + name
                + "' had no confirmation (no API match, no toast, row still present). Screenshot: "
                + (shot == null ? "<not captured>" : shot));
        return false;
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    /** The tree row whose accessible name starts with {@code name} (its own "Open actions menu" suffix excluded). */
    private Locator findTreeItemByName(String name) {
        SoakUiUtils.expandCollapsedTreeNodes(page, 6, 10000);
        return page.getByRole(AriaRole.TREEITEM, new Page.GetByRoleOptions()
                .setName(Pattern.compile("^" + escapeRegex(name), Pattern.CASE_INSENSITIVE))).first();
    }

    /**
     * Escapes regex metacharacters for a pattern handed to a Playwright locator. Must never use
     * {@link Pattern#quote} for this: Playwright serializes a compiled Pattern's source string
     * as-is to build a JavaScript RegExp in the browser, and Java's {@code \Q...\E} quoting syntax
     * is not valid JS regex - confirmed live, a Pattern built with {@code Pattern.quote(name)} here
     * silently matched nothing at all, every time, regardless of input (its {@code \Q}/{@code \E}
     * markers became literal, unmatched characters once translated to JS). Plain per-character
     * backslash escaping, as used here, is valid in both Java and JS regex.
     */
    private static String escapeRegex(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (".^$|?*+()[]{}\\".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** Clicks {@code menuButton} then the menu item named {@code itemName}; closes the menu again on any miss. */
    private boolean clickMenuButtonAndItem(Locator menuButton, String itemName) {
        if (menuButton.count() == 0) {
            return false;
        }
        try {
            menuButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            return false;
        }
        Locator item = page.getByRole(AriaRole.MENUITEM,
                new Page.GetByRoleOptions().setName(itemName).setExact(false)).first();
        if (!SoakUiUtils.waitVisible(item, 3000)) {
            closeMenu();
            return false;
        }
        try {
            item.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            return true;
        } catch (Exception exception) {
            closeMenu();
            return false;
        }
    }

    /** Closes an open actions-menu popover (not a modal dialog, so {@code closeOpenDialogs} won't catch it). */
    private void closeMenu() {
        try {
            page.keyboard().press("Escape");
            page.waitForTimeout(200);
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    /** Clicks Save changes and blocks on the create/update API resolving (pass or fail); screenshots on failure. */
    private boolean saveAndVerify(Locator dialog, Locator saveButton, String action, String rowName) {
        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(
                page, saveButton, this::isProjectHierarchyWriteResponse, 15000);
        String toast = waitForToast(toastBefore, 6000);

        if (response != null) {
            int status = response.status();
            boolean pass = status >= 200 && status < 300;
            System.out.println("[PROJECT HIERARCHY]   " + action + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + status + " (" + (pass ? "PASS" : "FAIL") + ")"
                    + (toast.isBlank() ? "" : " | toast: \"" + toast + "\""));
            if (!pass) {
                String shot = capture(SoakUiUtils.slug(action) + "-api-failed");
                System.err.println("[PROJECT HIERARCHY]   " + action + " FAILED for '" + rowName
                        + "' (HTTP " + status + "). Screenshot: " + (shot == null ? "<not captured>" : shot));
            }
            return pass;
        }

        System.out.println("[PROJECT HIERARCHY]   " + action + ": no API response matched within 15s.");
        boolean dialogClosed = !SoakUiUtils.isVisibleQuietly(dialog.first());
        boolean badToast = !toast.isBlank()
                && Pattern.compile("fail|error|unable|could not", Pattern.CASE_INSENSITIVE).matcher(toast).find();
        boolean goodToast = !toast.isBlank()
                && Pattern.compile("success|saved|updated", Pattern.CASE_INSENSITIVE).matcher(toast).find();

        if (badToast) {
            String shot = capture(SoakUiUtils.slug(action) + "-failed");
            System.err.println("[PROJECT HIERARCHY]   " + action + " FAILED for '" + rowName
                    + "' (toast: \"" + toast + "\"). Screenshot: " + (shot == null ? "<not captured>" : shot));
            return false;
        }
        if (goodToast || dialogClosed) {
            System.out.println("[PROJECT HIERARCHY]   " + action + " accepted"
                    + (dialogClosed ? " (dialog closed)" : "") + (toast.isBlank() ? "" : " toast: \"" + toast + "\""));
            return true;
        }

        String shot = capture(SoakUiUtils.slug(action) + "-unconfirmed");
        System.err.println("[PROJECT HIERARCHY]   " + action + " for '" + rowName
                + "' had no confirmation (no API match, no toast, dialog still open). Screenshot: "
                + (shot == null ? "<not captured>" : shot));
        return false;
    }

    /** True for the response that backs a Project Hierarchy create/update/delete action. Broad on purpose. */
    private boolean isProjectHierarchyWriteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                    || "DELETE".equals(method);
            return write && (url.contains("node") || url.contains("site")
                    || url.contains("hierarchy") || url.contains("project"));
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Audit Logs (search + export) - runs after validateRandomNodeAndSite, before Devices
    // ---------------------------------------------------------------------

    /**
     * Opens the "Audit Logs" tab left available on the Project Hierarchy page after
     * {@link #validateRandomNodeAndSite()}'s own Node/Site lifecycle has run (and, by then, deleted
     * both rows it created) - unchanged by that method's rewrite to a Create/Update/Delete lifecycle,
     * since this method never depended on a specific row staying selected to begin with. Does not
     * select a tree row of its own - it only depends on a tab already being present, so it never
     * touches tree/selection state.
     *
     * <p>Search is exploratory rather than a fixed pass/fail: the search term used may or may not
     * match anything, so either the {@code no-data-image} empty state or real result rows is a
     * valid, expected outcome - both are simply logged. What is validated is the background API the
     * search fires (pass/fail by HTTP status) and, separately, that the export control produces a
     * real file download.
     *
     * <p>Contract: never throws into the caller.
     */
    public boolean validateAuditLogs() {
        System.out.println(SEP);
        System.out.println("PROJECT HIERARCHY - AUDIT LOGS (search + export)");
        System.out.println(SEP);

        Locator auditTab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Audit Logs").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(auditTab, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[PROJECT HIERARCHY]   'Audit Logs' tab not present; skipping.");
            return false;
        }
        try {
            auditTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY]   'Audit Logs' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator auditPage = page.locator(".ph-v1-page__audit-logs").first();
        if (!SoakUiUtils.waitVisible(auditPage, ELEMENT_TIMEOUT_MS)) {
            String shot = capture("audit-logs-not-loaded");
            System.err.println("[PROJECT HIERARCHY]   Audit Logs page did not load. Screenshot: "
                    + (shot == null ? "<not captured>" : shot));
            return false;
        }
        System.out.println("[PROJECT HIERARCHY]   Audit Logs page loaded.");

        boolean searchOk = searchAuditLogs();
        // Non-blocking: clearing an already-cleared date filter is a no-op, and its own absence
        // (a build with no active date filter to begin with) is not itself a failure - only search
        // and export are the hard gates below, unchanged from before this was added.
        clearDateFilter();
        boolean exportOk = exportAuditLogs();
        return searchOk && exportOk;
    }

    /**
     * Clicks "Clear Date & Time filter" if present, leaving Audit Logs unfiltered by date before
     * export - confirmed live: the button is directly available without first opening the
     * "Date & Time : ..." dropdown (whose own accessible name carries a live date range, so it is
     * never matched by a fixed name). A missing button just skips (dates may already be clear).
     */
    private boolean clearDateFilter() {
        Locator clearDate = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Clear Date & Time filter").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(clearDate, 3000)) {
            System.out.println("[PROJECT HIERARCHY]   Audit Logs: 'Clear Date & Time filter' not present;"
                    + " skipping.");
            return true;
        }
        try {
            clearDate.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(400);
            System.out.println("[PROJECT HIERARCHY]   Audit Logs: date filter cleared.");
            return true;
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY]   'Clear Date & Time filter' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Fills the Audit Logs search box, waits for its API to resolve (pass/fail by HTTP status),
     * then logs whether the result is the {@code no-data-image} empty state or real rows - both are
     * valid outcomes for an arbitrary search term, so neither fails this check on its own. Clears
     * the search afterwards so the tab is left as it was found.
     */
    private boolean searchAuditLogs() {
        Locator searchBox = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Search Devices Audit Logs").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(searchBox, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[PROJECT HIERARCHY]   Audit Logs: search box not present; skipping search check.");
            return true;
        }

        String term = ConfigReader.getOrDefault("project.hierarchy.audit.log.search.term", "dg");
        Response response;
        try {
            response = page.waitForResponse(this::isAuditLogResponse,
                    new Page.WaitForResponseOptions().setTimeout(10000),
                    () -> searchBox.fill(term));
        } catch (Exception exception) {
            response = null;
        }

        boolean apiOk = true;
        if (response != null) {
            int status = response.status();
            apiOk = status >= 200 && status < 300;
            System.out.println("[PROJECT HIERARCHY]   Audit Logs search API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + status + " (" + (apiOk ? "PASS" : "FAIL") + ")");
            if (!apiOk) {
                String shot = capture("audit-logs-search-api-failed");
                System.err.println("[PROJECT HIERARCHY]   Audit Logs search API FAILED (HTTP " + status
                        + "). Screenshot: " + (shot == null ? "<not captured>" : shot));
            }
        } else {
            System.out.println("[PROJECT HIERARCHY]   Audit Logs search: no matching API response observed"
                    + " within 10s (result checked from the rendered page instead).");
        }

        boolean noDataShown = SoakUiUtils.waitVisible(page.getByTestId("no-data-image"), 5000);
        System.out.println("[PROJECT HIERARCHY]   Search '" + term + "' -> "
                + (noDataShown ? "no data (no-data-image shown)." : "data available (no-data-image not shown)."));

        Locator clearButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Clear search").setExact(false)).first();
        if (SoakUiUtils.waitVisible(clearButton, 3000)) {
            try {
                clearButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(400);
                System.out.println("[PROJECT HIERARCHY]   Search cleared.");
            } catch (Exception exception) {
                System.err.println("[PROJECT HIERARCHY]   'Clear search' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }
        return apiOk;
    }

    /** True for the response backing the Audit Logs search/list API. Broad on purpose. */
    private boolean isAuditLogResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return "GET".equals(method) && (url.contains("audit") || url.contains("log"));
        } catch (Exception exception) {
            return false;
        }
    }

    /** Clicks "Export audit logs" and confirms a real file download starts. */
    private boolean exportAuditLogs() {
        Locator exportButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Export audit logs").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(exportButton, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[PROJECT HIERARCHY]   Audit Logs: 'Export audit logs' control not present;"
                    + " skipping export check.");
            return true;
        }
        try {
            com.microsoft.playwright.Download download = page.waitForDownload(
                    new Page.WaitForDownloadOptions().setTimeout(15000),
                    () -> exportButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS)));
            System.out.println("[PROJECT HIERARCHY]   Audit Logs export downloaded: " + download.suggestedFilename());
            return true;
        } catch (Exception exception) {
            String shot = capture("audit-logs-export-failed");
            System.err.println("[PROJECT HIERARCHY]   Audit Logs export FAILED: "
                    + SoakUiUtils.firstLine(exception.getMessage())
                    + ". Screenshot: " + (shot == null ? "<not captured>" : shot));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Polls up to {@code timeoutMs} for a toast different from {@code before}. */
    private String waitForToast(String before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                return toast;
            }
            page.waitForTimeout(500);
        }
        return "";
    }

    private String capture(String name) {
        return ScreenshotUtils.captureTo(page, screenshotDirectory, SoakUiUtils.slug(name));
    }

    private static String safeMethod(Response response) {
        try {
            return response.request().method();
        } catch (Exception exception) {
            return "?";
        }
    }

    private static String shortPath(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            return (path == null || path.isBlank()) ? url : path;
        } catch (Exception exception) {
            return url;
        }
    }

}
