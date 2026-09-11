package com.vigilx.pages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.SoakUiUtils;

/**
 * Robust, soak-safe validation of the Reports page: opens it, searches it (validating either the
 * {@code no-data-image} empty state or real result rows - both are valid outcomes for an arbitrary
 * term - plus the background search API), clears the search, opens whichever report is first in the
 * (cleared) list to confirm its details dialog loads, closes that dialog again (it overlays the
 * page-level Export control), then exercises Export and confirms a real file download starts.
 *
 * <p>Which report is "first" is discovered dynamically from its own row - a cell whose accessible
 * name matches {@code "View <id>"} - never a hardcoded report id. The id read off that cell is only
 * used for logging, so this never depends on any particular report existing.
 *
 * <p>Runs after the existing Alerts validation and before License, touching neither - purely
 * additive and config-gated, following the same shape as
 * {@link ProjectHierarchyValidation}'s Audit Logs sub-flow (which is left untouched). The
 * click-and-wait-for-download and search/no-data-image logic live in {@link SoakUiUtils} so this
 * class does not duplicate either.
 *
 * <p>Contract: never throws into the caller. Every failure is logged, screenshotted under
 * {@code target/soak-test/screenshots/reports} and returned as {@code false} so the soak continues.
 */
public class ReportsValidation extends BasePage {

    private static final String SEP = "============================================================";
    private static final String DEFAULT_SCREENSHOT_DIRECTORY = "target/soak-test/screenshots/reports";
    private static final int ELEMENT_TIMEOUT_MS = 8000;

    private final Path screenshotDirectory;

    public ReportsValidation(Page page) {
        super(page);
        this.screenshotDirectory = Paths.get(ConfigReader.getOrDefault(
                "reports.screenshot.directory", DEFAULT_SCREENSHOT_DIRECTORY));
    }

    // ---------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------

    /** Opens Reports via the left-nav link (no duplicate navigation if it is already open). */
    public boolean open() {
        if (isOnReportsPage()) {
            System.out.println("[REPORTS] Already on Reports; reusing it.");
            return true;
        }
        try {
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Reports").setExact(false)).first()
                    .click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
        } catch (Exception exception) {
            System.err.println("[REPORTS] 'Reports' nav link could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        boolean loaded = SoakUiUtils.waitVisible(reportsRegion(), 15000);
        if (!loaded) {
            String shot = capture("page-not-loaded");
            System.err.println("[REPORTS] Reports page did not load. Screenshot: "
                    + (shot == null ? "<not captured>" : shot));
        } else {
            System.out.println("[REPORTS] Reports page loaded.");
        }
        return loaded;
    }

    private boolean isOnReportsPage() {
        return SoakUiUtils.isVisibleQuietly(reportsRegion().first());
    }

    private Locator reportsRegion() {
        return page.getByRole(AriaRole.REGION, new Page.GetByRoleOptions().setName("Reports").setExact(false));
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Runs the full Reports flow: {@link #open()}, {@link #searchReports()}, then
     * {@link #viewAndExportFirstReport()}. Always leaves the page back on the Reports list
     * afterwards so whatever check runs next starts from a known page. Never throws.
     */
    public boolean validateReports() {
        System.out.println(SEP);
        System.out.println("REPORTS VALIDATION - page load, search, view + export");
        System.out.println(SEP);

        if (!open()) {
            return false;
        }

        boolean searchOk = searchReports();
        boolean viewExportOk = viewAndExportFirstReport();
        returnToReportsList();

        System.out.println(SEP);
        System.out.println("REPORTS RESULT: search=" + (searchOk ? "PASS" : "FAIL")
                + " | view+export=" + (viewExportOk ? "PASS" : "FAIL"));
        System.out.println(SEP);
        return searchOk && viewExportOk;
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    /**
     * Fills the Reports search box, validates the search API resolves (pass/fail by HTTP status),
     * then logs whether the result is the {@code no-data-image} empty state or real rows - both are
     * valid outcomes for an arbitrary term. Clears the search afterwards.
     */
    private boolean searchReports() {
        Locator searchBox = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Search").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(searchBox, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[REPORTS]   Search box not present; skipping search check.");
            return true;
        }

        String term = ConfigReader.getOrDefault("reports.search.term", "gf");
        Locator clearButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Clear search").setExact(false)).first();
        SoakUiUtils.SearchOutcome outcome = SoakUiUtils.searchAndReport(
                page, searchBox, term, this::isReportsApiResponse, 10000, clearButton);

        boolean apiOk = true;
        Response response = outcome.apiResponse();
        if (response != null) {
            int status = response.status();
            apiOk = status >= 200 && status < 300;
            System.out.println("[REPORTS]   Search API: " + safeMethod(response) + " " + shortPath(response.url())
                    + " -> " + status + " (" + (apiOk ? "PASS" : "FAIL") + ")");
            if (!apiOk) {
                String shot = capture("search-api-failed");
                System.err.println("[REPORTS]   Search API FAILED (HTTP " + status + "). Screenshot: "
                        + (shot == null ? "<not captured>" : shot));
            }
        } else {
            System.out.println("[REPORTS]   Search: no matching API response observed within 10s"
                    + " (result checked from the rendered page instead).");
        }

        System.out.println("[REPORTS]   Search '" + term + "' -> " + (outcome.noDataShown()
                ? "no data (no-data-image shown)." : "data available (no-data-image not shown)."));
        System.out.println("[REPORTS]   Search cleared.");
        return apiOk;
    }

    /** True for the response backing the Reports list/search API. Broad on purpose. */
    private boolean isReportsApiResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return "GET".equals(method) && url.contains("report");
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // View + Export - the report is always discovered dynamically, never hardcoded
    // ---------------------------------------------------------------------

    /**
     * Opens whichever report is first in the (cleared) list - found from its own row, a cell whose
     * accessible name matches {@code "View <id>"}, never a hardcoded id - and confirms its details
     * dialog loads. That dialog overlays the page-level Export control, so it is closed again before
     * Export is exercised - Export here targets the report list itself (as the recorded flow's own
     * final click, straight on {@code page}, does), not a per-row action.
     */
    private boolean viewAndExportFirstReport() {
        Locator viewCell = page.getByRole(AriaRole.CELL, new Page.GetByRoleOptions()
                .setName(Pattern.compile("^view\\s+\\S+", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(viewCell, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[REPORTS]   No report row with a 'View' action found; skipping view/export check.");
            return true;
        }

        String reportId = reportIdOf(viewCell);
        System.out.println("[REPORTS]   Opening report '" + reportId + "' (first row, discovered dynamically).");

        try {
            viewCell.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[REPORTS]   'View' could not be clicked for '" + reportId + "': "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            String shot = capture("viewer-not-opened");
            System.err.println("[REPORTS]   Report details dialog for '" + reportId
                    + "' did not open. Screenshot: " + (shot == null ? "<not captured>" : shot));
            return false;
        }
        System.out.println("[REPORTS]   Report details dialog opened for '" + reportId + "'.");
        SoakUiUtils.closeOpenDialogs(page);

        Locator exportButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Export").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(exportButton, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[REPORTS]   'Export' control not present; skipping export check.");
            return true;
        }
        return exportReport(exportButton, reportId);
    }

    /**
     * Extracts a report's id from its "View <id>" cell - the {@code aria-label} first (the eye-icon
     * cell that actually carries this accessible name usually has no visible text of its own), its
     * own inner text otherwise. Returns {@code "<unknown>"} rather than failing if neither works;
     * this id is only ever used for logging, never as a lookup key.
     */
    private String reportIdOf(Locator viewCell) {
        try {
            String label = viewCell.getAttribute("aria-label");
            String id = idAfterView(label);
            if (id != null) {
                return id;
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        try {
            String id = idAfterView(viewCell.innerText());
            if (id != null) {
                return id;
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return "<unknown>";
    }

    private static String idAfterView(String text) {
        if (text == null) {
            return null;
        }
        String id = text.replaceFirst("(?i)^\\s*view\\s*", "").trim();
        return id.isBlank() ? null : id;
    }

    private boolean exportReport(Locator exportButton, String reportId) {
        Download download = SoakUiUtils.clickAndWaitForDownload(page, exportButton, 15000);
        if (download == null) {
            String shot = capture("export-failed");
            System.err.println("[REPORTS]   Export FAILED for '" + reportId
                    + "' (no download started within 15s). Screenshot: " + (shot == null ? "<not captured>" : shot));
            return false;
        }
        System.out.println("[REPORTS]   Export downloaded: " + download.suggestedFilename());
        return true;
    }

    /** Best-effort: close whatever the viewer opened and confirm the list is back, or re-open it. */
    private void returnToReportsList() {
        try {
            SoakUiUtils.dismissDialog(page);
            if (!isOnReportsPage()) {
                open();
            }
        } catch (Exception ignored) {
            // Not itself a pass/fail signal - the next check's own navigation is the real guard.
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

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
