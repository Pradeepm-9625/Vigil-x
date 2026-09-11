package com.vigilx.pages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.SoakUiUtils;

/**
 * Robust, soak-safe validation of the Project Hierarchy tree: selects one random Node and one
 * random Site, then exercises each row's Rename -&gt; Save changes flow and validates the save API
 * resolves (pass or fail), screenshotting and logging any failure.
 *
 * <p>Additional to {@link ApplicationHealthPage#validateProjectHierarchy}, which is left untouched
 * and is reused here (via {@link #open()}) instead of re-implementing the same navigation. Which
 * tree row is a Node vs a Site is discovered directly from each row's own "Open actions menu" (the
 * three-dot button): a "Rename Node" item means the row is a Node, "Rename Site" means a Site,
 * anything else (a Device row) is skipped - nothing about the tree shape is hardcoded, so one
 * shared method ({@link #editSelectedItem}) drives both flows instead of two near-duplicate ones.
 * A row whose kind is already satisfied is skipped immediately, before its menu is even opened, so
 * a tree dominated by one kind never wastes a full Rename/Save/API cycle re-editing rows the result
 * already has. The dialog-closing, toast-reading and click-and-wait-for-API logic all come from
 * {@link SoakUiUtils} rather than being copied again here.
 *
 * <p>Read-only in effect: "Save changes" is clicked with the details left exactly as loaded (same
 * as the recorded flow), so no field value is ever changed - only the row's own Save action fires.
 *
 * <p>{@link #validateAuditLogs()} runs afterwards, on whichever row {@link #validateRandomNodeAndSite()}
 * left selected: opens the "Audit Logs" tab, searches it and validates the background search API plus
 * the rendered result (either the {@code no-data-image} empty state or real rows - both valid), clears
 * the search, then exercises the export control and confirms a real file download starts.
 *
 * <p>Contract: never throws into the caller. Every failure is logged, screenshotted under
 * {@code target/soak-test/screenshots/project-hierarchy} and returned as {@code false} so the soak
 * continues.
 */
public class ProjectHierarchyValidation extends BasePage {

    private static final String SEP = "============================================================";
    private static final String DEFAULT_SCREENSHOT_DIRECTORY = "target/soak-test/screenshots/project-hierarchy";
    private static final int ELEMENT_TIMEOUT_MS = 8000;
    /** Tree rows tried, at most, while looking for one Node and one Site row. */
    private static final int MAX_ATTEMPTS = 12;

    private final Path screenshotDirectory;

    public ProjectHierarchyValidation(Page page) {
        super(page);
        this.screenshotDirectory = Paths.get(ConfigReader.getOrDefault(
                "project.hierarchy.screenshot.directory", DEFAULT_SCREENSHOT_DIRECTORY));
    }

    /** One row's edit-and-save outcome. */
    private record EditOutcome(String kind, String name, boolean passed) { }

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
     * Picks one random Node and one random Site from the tree (expanding collapsed branches as
     * needed) and runs {@link #editSelectedItem} on each. Returns {@code true} only when both a
     * Node and a Site were found and each Save was confirmed by its API.
     */
    public boolean validateRandomNodeAndSite() {
        System.out.println(SEP);
        System.out.println("PROJECT HIERARCHY VALIDATION - random Node + Site edit/save");
        System.out.println(SEP);

        SoakUiUtils.expandCollapsedTreeNodes(page, 6, 20000);

        List<Locator> items = treeItems();
        Collections.shuffle(items, new Random(resolveSeed()));
        System.out.println("[PROJECT HIERARCHY] " + items.size() + " tree row(s) available.");

        EditOutcome nodeOutcome = null;
        EditOutcome siteOutcome = null;
        int attempts = 0;

        for (Locator item : items) {
            boolean needNode = nodeOutcome == null;
            boolean needSite = siteOutcome == null;
            if ((!needNode && !needSite) || attempts >= MAX_ATTEMPTS) {
                break;
            }
            attempts++;
            // Only the kind(s) still missing are worth a full Rename -> Save -> API cycle; a row
            // whose kind is already satisfied is skipped immediately (see editSelectedItem), so a
            // tree dominated by one kind never burns time re-editing rows the result already has.
            EditOutcome outcome = editSelectedItem(item, needNode, needSite);
            if (outcome == null) {
                continue;
            }
            if ("Node".equalsIgnoreCase(outcome.kind()) && nodeOutcome == null) {
                nodeOutcome = outcome;
            } else if ("Site".equalsIgnoreCase(outcome.kind()) && siteOutcome == null) {
                siteOutcome = outcome;
            }
        }

        boolean nodeOk = nodeOutcome != null && nodeOutcome.passed();
        boolean siteOk = siteOutcome != null && siteOutcome.passed();

        System.out.println(SEP);
        System.out.println("PROJECT HIERARCHY RESULT (after " + attempts + " row(s) tried)");
        System.out.println("Node : " + (nodeOutcome == null ? "NOT FOUND"
                : nodeOutcome.name() + " -> " + (nodeOk ? "PASS" : "FAIL")));
        System.out.println("Site : " + (siteOutcome == null ? "NOT FOUND"
                : siteOutcome.name() + " -> " + (siteOk ? "PASS" : "FAIL")));
        System.out.println(SEP);

        if (nodeOutcome == null) {
            System.err.println("[PROJECT HIERARCHY] No Node row found to edit in " + attempts + " attempt(s).");
        }
        if (siteOutcome == null) {
            System.err.println("[PROJECT HIERARCHY] No Site row found to edit in " + attempts + " attempt(s).");
        }
        return nodeOk && siteOk;
    }

    // ---------------------------------------------------------------------
    // Shared edit-and-save flow (Node and Site both go through this - no duplicate code)
    // ---------------------------------------------------------------------

    /**
     * Opens one tree row's own "Open actions menu" (the three-dot button) and reads which
     * {@code Rename <Kind>} item it offers - "Rename Node" means this row is a Node, "Rename Site"
     * means a Site, anything else (a Device row, or none at all) is skipped immediately. This is
     * the direct, per-row signal the app itself provides, so it replaces selecting the row and
     * inferring its kind from a details panel - simpler and not dependent on that panel's own
     * timing/rendering.
     *
     * <p>A row whose kind is already satisfied ({@code needNode}/{@code needSite} false for it) is
     * also skipped immediately, before the menu is even opened - only a row that could still fill
     * the missing Node or Site slot goes through the full Rename -&gt; Save -&gt; API cycle. Never
     * throws: returns {@code null} for any row that does not end up contributing an outcome.
     */
    private EditOutcome editSelectedItem(Locator item, boolean needNode, boolean needSite) {
        String rowName = rowName(item);

        Locator menuButton = item.getByLabel("Open actions menu").first();
        if (menuButton.count() == 0) {
            menuButton = item.locator(
                    ".ph-v1-dynamic-tree-node__trailing .MuiButtonBase-root, .MuiTreeItem-content button").first();
        }
        if (menuButton.count() == 0) {
            System.out.println("[PROJECT HIERARCHY]   '" + rowName + "' has no actions menu; skipping.");
            return null;
        }

        try {
            item.scrollIntoViewIfNeeded();
            menuButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.out.println("[PROJECT HIERARCHY]   '" + rowName + "' actions menu could not be opened; skipping.");
            return null;
        }

        Locator renameItem = page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions()
                .setName(Pattern.compile("^rename\\s+\\w+", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(renameItem, 3000)) {
            System.out.println("[PROJECT HIERARCHY]   '" + rowName + "' offers no Rename Node/Site action; skipping.");
            closeMenu();
            return null;
        }

        String kind = renameKind(renameItem);
        boolean isNode = "Node".equalsIgnoreCase(kind);
        boolean isSite = "Site".equalsIgnoreCase(kind);
        if (!isNode && !isSite) {
            System.out.println("[PROJECT HIERARCHY]   '" + rowName + "' is a " + kind
                    + " row; skipping (only Node and Site are in scope).");
            closeMenu();
            return null;
        }
        if ((isNode && !needNode) || (isSite && !needSite)) {
            System.out.println("[PROJECT HIERARCHY]   '" + rowName + "' is a " + kind
                    + " row; already have one, skipping.");
            closeMenu();
            return null;
        }
        System.out.println("[PROJECT HIERARCHY] Row '" + rowName + "' identified as " + kind
                + " (via Rename " + kind + ").");

        try {
            renameItem.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY]   'Rename " + kind + "' for '" + rowName
                    + "' could not be clicked: " + SoakUiUtils.firstLine(exception.getMessage()));
            closeMenu();
            return new EditOutcome(kind, rowName, false);
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            capture("edit-" + SoakUiUtils.slug(kind) + "-dialog-not-open");
            System.err.println("[PROJECT HIERARCHY]   Rename " + kind + " dialog did not open for '" + rowName + "'.");
            SoakUiUtils.closeOpenDialogs(page);
            return new EditOutcome(kind, rowName, false);
        }

        // Straight to Save: no dialog-content/field-presence checks here - the dialog opening is
        // already confirmed above, and the point of this flow is Edit -> Save -> validate the
        // background API, not re-verifying form fields Save itself will surface as a failure.
        Locator saveButton = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Save changes").setExact(false)).first();

        // Brief settle wait: the dialog's own data can still be hydrating right after it opens,
        // which was seen to occasionally let the very first Save click race ahead of the form
        // actually being interactive (a later, identical click on another row succeeded cleanly).
        page.waitForTimeout(800);
        boolean passed = saveAndVerify(dialog, saveButton, kind, rowName);

        SoakUiUtils.dismissDialog(page);
        return new EditOutcome(kind, rowName, passed);
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

    /** Clicks Save changes and blocks on the save API resolving (pass or fail); screenshots on failure. */
    private boolean saveAndVerify(Locator dialog, Locator saveButton, String kind, String rowName) {
        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(
                page, saveButton, this::isProjectHierarchySaveResponse, 15000);
        String toast = waitForToast(toastBefore, 6000);

        if (response != null) {
            int status = response.status();
            boolean pass = status >= 200 && status < 300;
            System.out.println("[PROJECT HIERARCHY]   Save " + kind + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + status + " (" + (pass ? "PASS" : "FAIL") + ")"
                    + (toast.isBlank() ? "" : " | toast: \"" + toast + "\""));
            if (!pass) {
                String shot = capture("edit-" + SoakUiUtils.slug(kind) + "-save-api-failed");
                System.err.println("[PROJECT HIERARCHY]   Save " + kind + " FAILED for '" + rowName
                        + "' (HTTP " + status + "). Screenshot: " + (shot == null ? "<not captured>" : shot));
            }
            return pass;
        }

        System.out.println("[PROJECT HIERARCHY]   Save " + kind + ": no save API response matched within 15s.");
        boolean dialogClosed = !SoakUiUtils.isVisibleQuietly(dialog.first());
        boolean badToast = !toast.isBlank()
                && Pattern.compile("fail|error|unable|could not", Pattern.CASE_INSENSITIVE).matcher(toast).find();
        boolean goodToast = !toast.isBlank()
                && Pattern.compile("success|saved|updated", Pattern.CASE_INSENSITIVE).matcher(toast).find();

        if (badToast) {
            String shot = capture("edit-" + SoakUiUtils.slug(kind) + "-save-failed");
            System.err.println("[PROJECT HIERARCHY]   Save " + kind + " FAILED for '" + rowName
                    + "' (toast: \"" + toast + "\"). Screenshot: " + (shot == null ? "<not captured>" : shot));
            return false;
        }
        if (goodToast || dialogClosed) {
            System.out.println("[PROJECT HIERARCHY]   Save " + kind + " accepted"
                    + (dialogClosed ? " (dialog closed)" : "") + (toast.isBlank() ? "" : " toast: \"" + toast + "\""));
            return true;
        }

        String shot = capture("edit-" + SoakUiUtils.slug(kind) + "-save-unconfirmed");
        System.err.println("[PROJECT HIERARCHY]   Save " + kind + " for '" + rowName
                + "' had no confirmation (no API match, no toast, dialog still open). Screenshot: "
                + (shot == null ? "<not captured>" : shot));
        return false;
    }

    /** True for the response that backs a Project Hierarchy Save changes click. Broad on purpose. */
    private boolean isProjectHierarchySaveResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
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
     * Opens the "Audit Logs" tab left available on whichever Node/Site row
     * {@link #validateRandomNodeAndSite()} selected, then exercises search and export. Does not
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
    // Tree discovery
    // ---------------------------------------------------------------------

    private List<Locator> treeItems() {
        List<Locator> items = new ArrayList<>();
        try {
            Locator rows = page.getByRole(AriaRole.TREEITEM);
            int count = rows.count();
            for (int index = 0; index < count; index++) {
                items.add(rows.nth(index));
            }
        } catch (Exception exception) {
            System.err.println("[PROJECT HIERARCHY] Could not list tree rows: " + exception.getMessage());
        }
        return items;
    }

    private String rowName(Locator item) {
        try {
            String name = item.getAttribute("aria-label");
            if (name != null && !name.isBlank()) {
                return name.replaceAll("(?i)\\s*open actions menu\\s*$", "").trim();
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        try {
            String text = item.textContent();
            return text == null ? "<unnamed row>" : text.trim().replaceAll("\\s+", " ");
        } catch (Exception exception) {
            return "<unnamed row>";
        }
    }

    /** "Rename Node" -&gt; "Node"; "Rename Site" -&gt; "Site"; "Rename Device" -&gt; "Device", etc. */
    private String renameKind(Locator renameItem) {
        try {
            String text = renameItem.textContent();
            if (text != null) {
                String kind = text.replaceFirst("(?i)^\\s*rename\\s+", "").trim();
                if (!kind.isBlank()) {
                    return kind;
                }
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return "Item";
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

    private long resolveSeed() {
        String configured = ConfigReader.getOrDefault("project.hierarchy.random.seed", "");
        if (configured != null && !configured.isBlank()) {
            try {
                return Long.parseLong(configured.trim());
            } catch (NumberFormatException ignored) {
                // Fall through to a fresh seed.
            }
        }
        return System.nanoTime();
    }
}
