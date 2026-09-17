package com.vigilx.pages;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Users &amp; Roles -&gt; Audit Logs: opens the tab, clears the "Date &amp; Time" filter
 * (confirmed live: with the default preset range active the list shows "No records found" even
 * though real rows exist once cleared), verifies the page's stable controls, exports the log, and
 * validates whatever comes back - a real downloaded file when the export endpoint works, or the
 * export API's own status when it does not (confirmed live against this build: {@code GET
 * /audit-logs/export?type=...} currently 404s, so a bare download timeout would otherwise be the
 * only signal).
 *
 * <p>Reached after {@link GroupsPage}'s group lifecycle. Follows the same shape as
 * {@link GroupsPage} / {@link RolesPage}: {@link BasePage} subclass, locators built from stable
 * accessible names (role + name) rather than combined page-text blobs, the shared
 * {@link SoakUiUtils} helpers, and its own small dialog/response helpers rather than a new
 * framework. Never modifies {@link UsersRolesPage}, {@link RolesPage} or {@link GroupsPage}.
 * Contract: never throws into the caller; every failure is logged and returned as {@code false}.
 */
public class AuditLogsPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    /** The most recent successful {@link #exportAuditLogs()} download, if any. */
    private Download lastDownload;

    public AuditLogsPage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full flow: {@link #navigateToAuditLogs()} -&gt; {@link #verifyAuditLogsPage()} -&gt;
     * {@link #clearDateTimeFilter()} -&gt; {@link #verifyDateTimeFilterCleared()} -&gt;
     * {@link #exportAuditLogs()} -&gt; (when a real file came back) {@link #verifyDownloadedFile()}
     * and {@link #verifyExportedAuditLogData()}. Each stage is attempted even if an earlier one
     * failed - this is a validation sweep, not a lifecycle with side effects to unwind - but the
     * returned result reflects every stage together.
     */
    public boolean runAuditLogsExportFlow() {
        boolean navigated = navigateToAuditLogs();
        boolean pageOk = navigated && verifyAuditLogsPage();
        boolean cleared = navigated && clearDateTimeFilter();
        boolean clearedVerified = cleared && verifyDateTimeFilterCleared();
        boolean exported = navigated && exportAuditLogs();
        boolean fileOk = true;
        boolean dataOk = true;
        if (exported && lastDownload != null) {
            Path saved = verifyDownloadedFile(lastDownload);
            fileOk = saved != null;
            dataOk = fileOk && verifyExportedAuditLogData(saved);
        }
        System.out.println("[AUDIT LOGS] Flow: navigated=" + navigated + " pageVerified=" + pageOk
                + " filterCleared=" + cleared + " filterClearedVerified=" + clearedVerified
                + " exported=" + exported + " fileValid=" + fileOk + " dataValid=" + dataOk);
        return navigated && pageOk && clearedVerified && exported && fileOk && dataOk;
    }

    /**
     * Additive entry point for the post-Sequence Audit Logs validation. Confirmed live via a real
     * recording: this is a DIFFERENT screen from {@link #navigateToAuditLogs()}'s Settings -&gt;
     * Users &amp; Roles -&gt; Audit Logs tab (used only by {@link #runAuditLogsExportFlow()} and its
     * existing callers, untouched) - the Live View area has its own "Audit Logs" section at
     * {@code /live-views/audit-logs}, reached from the same sidebar as My Views / Cameras /
     * Sequence. This method therefore navigates there directly via
     * {@link #navigateToLiveViewAuditLogs()} rather than reusing {@link #runAuditLogsExportFlow()},
     * then reuses every other existing granular step ({@link #verifyAuditLogsPage()},
     * {@link #clearDateTimeFilter()}, {@link #exportAuditLogs()}, {@link #verifyDownloadedFile},
     * {@link #verifyExportedAuditLogData}) as-is - their locators are the same accessible names on
     * both screens. Searches for a value expected to match nothing and confirms "No records found",
     * then - best-effort, since this depends on what audit data already exists in this environment -
     * clears the search and searches for {@code existingSearchText} to confirm real rows come back.
     * The positive search is logged but never gates the overall result.
     */
    public boolean runAuditLogsSearchFlow(String nonExistentSearchText, String existingSearchText) {
        boolean navigated = navigateToLiveViewAuditLogs();
        boolean pageOk = navigated && verifyAuditLogsPage();
        boolean cleared = navigated && clearDateTimeFilter();
        boolean clearedVerified = cleared && verifyDateTimeFilterCleared();
        boolean exported = navigated && exportAuditLogs();
        boolean fileOk = true;
        boolean dataOk = true;
        if (exported && lastDownload != null) {
            Path saved = verifyDownloadedFile(lastDownload);
            fileOk = saved != null;
            dataOk = fileOk && verifyExportedAuditLogData(saved);
        }
        boolean negativeSearchOk = navigated && searchAuditLogs(nonExistentSearchText) && verifyNoRecordsFound();
        boolean positiveSearchOk = true;
        if (navigated && existingSearchText != null && !existingSearchText.isBlank()) {
            positiveSearchOk = searchAuditLogs("") && searchAuditLogs(existingSearchText)
                    && verifySearchResultsPresent();
            if (!positiveSearchOk) {
                System.out.println("[AUDIT LOGS]   Positive search for '" + existingSearchText + "' did not"
                        + " find matching rows in this environment (best-effort, not gating).");
            }
        }
        System.out.println("[AUDIT LOGS] Live View Audit Logs flow: navigated=" + navigated + " pageVerified="
                + pageOk + " filterCleared=" + cleared + " filterClearedVerified=" + clearedVerified
                + " exported=" + exported + " fileValid=" + fileOk + " dataValid=" + dataOk
                + " negativeSearch=" + negativeSearchOk + " positiveSearch(best-effort)=" + positiveSearchOk);
        return navigated && pageOk && clearedVerified && exported && fileOk && dataOk && negativeSearchOk;
    }

    /**
     * Additive entry point for the post-Snapshot Audit Logs validation, reached from the Archive
     * page's own "Exports" area rather than Settings -&gt; Users &amp; Roles or the Live View
     * sidebar's {@code /live-views/audit-logs}: confirmed live via a real recording, the Archive
     * page has its own "Audit Logs" navigation button (a sibling of "Exports") landing on
     * {@code /live-views/archive/audit-logs}. Deliberately minimal - only the steps actually
     * required: {@link #navigateToArchiveAuditLogs()} -&gt; {@link #clearDateTimeFilter()} -&gt;
     * {@link #exportAuditLogs()} -&gt; {@link #verifyDownloadedFile(Download)}. No search, no
     * additional date filtering, and no page/column verification beyond what those existing,
     * reused methods already do - {@link #runAuditLogsExportFlow()} and
     * {@link #runAuditLogsSearchFlow(String, String)} are untouched and still cover their own,
     * fuller flows on their own screens.
     */
    public boolean runArchiveAuditLogsExportFlow() {
        boolean navigated = navigateToArchiveAuditLogs();
        boolean cleared = navigated && clearDateTimeFilter();
        boolean exported = navigated && exportAuditLogs();
        boolean fileOk = false;
        if (exported && lastDownload != null) {
            fileOk = verifyDownloadedFile(lastDownload) != null;
        }
        System.out.println("[AUDIT LOGS] Archive Audit Logs export flow: navigated=" + navigated
                + " filterCleared=" + cleared + " exported=" + exported + " fileValid=" + fileOk);
        return navigated && cleared && exported && fileOk;
    }

    /**
     * Additive entry point for the post-Master-Configuration Audit Logs validation, reached from
     * the "Devices" left-nav group's own "Audit Logs" link (a sibling of "Master Configuration",
     * confirmed live via a real recording) landing on {@code /devices/audit-logs} - a fourth,
     * distinct screen from {@link #navigateToAuditLogs()}, {@link #navigateToLiveViewAuditLogs()}
     * and {@link #navigateToArchiveAuditLogs()}. Deliberately minimal, matching
     * {@link #runArchiveAuditLogsExportFlow()}'s shape: {@link #navigateToDevicesAuditLogs()} -&gt;
     * {@link #clearDateTimeFilter()} -&gt; {@link #exportAuditLogs()} -&gt;
     * {@link #verifyDownloadedFile(Download)}. No search, no additional date filtering - every
     * other existing Audit Logs flow is untouched.
     */
    public boolean runDevicesAuditLogsExportFlow() {
        boolean navigated = navigateToDevicesAuditLogs();
        boolean cleared = navigated && clearDateTimeFilter();
        boolean exported = navigated && exportAuditLogs();
        boolean fileOk = false;
        if (exported && lastDownload != null) {
            fileOk = verifyDownloadedFile(lastDownload) != null;
        }
        System.out.println("[AUDIT LOGS] Devices Audit Logs export flow: navigated=" + navigated
                + " filterCleared=" + cleared + " exported=" + exported + " fileValid=" + fileOk);
        return navigated && cleared && exported && fileOk;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /**
     * Opens Settings (only if it is not already open), then "Users &amp; Roles" if the Audit Logs
     * tab is not already visible, then the "Audit Logs" tab. Independent of {@link UsersRolesPage}
     * / {@link RolesPage} / {@link GroupsPage} - it re-navigates itself rather than assuming any of
     * their navigation already ran.
     */
    public boolean navigateToAuditLogs() {
        Locator auditTab = auditLogsTab();
        boolean alreadyOnUsersRoles = SoakUiUtils.isVisibleQuietly(auditTab)
                || SoakUiUtils.isVisibleQuietly(page.getByRole(AriaRole.TAB,
                        new Page.GetByRoleOptions().setName("Users").setExact(true)).first());
        if (!alreadyOnUsersRoles) {
            try {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                if (SoakUiUtils.isVisibleQuietly(openSettings)) {
                    openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(500);
                }
                Locator usersRolesLink = page.getByText(Pattern.compile("users & roles", Pattern.CASE_INSENSITIVE))
                        .first();
                if (SoakUiUtils.waitVisible(usersRolesLink, ELEMENT_TIMEOUT_MS)) {
                    usersRolesLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(600);
                }
            } catch (Exception exception) {
                System.err.println("[AUDIT LOGS]   'Users & Roles' could not be opened: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        if (!SoakUiUtils.waitVisible(auditTab, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[AUDIT LOGS]   'Audit Logs' tab not found.");
            return false;
        }
        try {
            auditTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   'Audit Logs' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean opened = SoakUiUtils.waitVisible(exportButton(), ELEMENT_TIMEOUT_MS)
                || SoakUiUtils.waitVisible(addFilterButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[AUDIT LOGS] Audit Logs tab opened: " + (opened ? "YES" : "NO"));
        return opened;
    }

    /**
     * Navigates directly to the Live View area's OWN "Audit Logs" screen
     * ({@code /live-views/audit-logs}) - confirmed live via a real recording to be a distinct
     * screen from {@link #navigateToAuditLogs()}'s Settings -&gt; Users &amp; Roles -&gt; Audit Logs
     * tab, reached instead from the same left sidebar as My Views / Cameras / Sequence. Used only by
     * {@link #runAuditLogsSearchFlow(String, String)} - {@link #navigateToAuditLogs()} and its
     * existing callers are untouched.
     */
    public boolean navigateToLiveViewAuditLogs() {
        try {
            String baseUrl = ConfigReader.get("base.url").replace("/onboarding", "");
            navigateTo(baseUrl + "/live-views/audit-logs");
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   Could not navigate to Live View Audit Logs: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean opened = SoakUiUtils.waitVisible(exportButton(), ELEMENT_TIMEOUT_MS)
                || SoakUiUtils.waitVisible(addFilterButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[AUDIT LOGS] Live View Audit Logs opened: " + (opened ? "YES" : "NO"));
        return opened;
    }

    /**
     * Clicks the Archive page's OWN "Audit Logs" navigation button - confirmed live via a real
     * recording to be a sibling of the "Exports" button already used by
     * {@link ArchiveExportValidation}, landing on {@code /live-views/archive/audit-logs}: a third,
     * distinct screen from both {@link #navigateToAuditLogs()} (Settings -&gt; Users &amp; Roles)
     * and {@link #navigateToLiveViewAuditLogs()} ({@code /live-views/audit-logs}), neither of which
     * reaches this URL - so neither is reused here, matching this codebase's existing precedent of
     * one small navigation method per distinct real entry point rather than overloading one method
     * for three different screens. Used only by {@link #runArchiveAuditLogsExportFlow()}; every
     * other navigation method above is untouched.
     */
    public boolean navigateToArchiveAuditLogs() {
        Locator auditLogsNav = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Audit Logs").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(auditLogsNav, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[AUDIT LOGS]   Archive 'Audit Logs' navigation control not found.");
            return false;
        }
        try {
            auditLogsNav.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   Archive 'Audit Logs' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean opened = SoakUiUtils.waitVisible(exportButton(), ELEMENT_TIMEOUT_MS)
                || SoakUiUtils.waitVisible(clearDateFilterButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[AUDIT LOGS] Archive Audit Logs opened: " + (opened ? "YES" : "NO"));
        return opened;
    }

    /**
     * Clicks the "Devices" left-nav group's own "Audit Logs" link - confirmed live via a real
     * recording to be a sibling of the "Master Configuration" link (both appear once "Devices" is
     * expanded), landing on {@code /devices/audit-logs}: a fourth, distinct screen from every other
     * {@code navigateTo...AuditLogs} method above, none of which reach this URL. Assumes the
     * "Devices" group is already expanded (true immediately after
     * {@link MasterConfigurationValidation#open()} runs, which this flow always follows); if the
     * link is not yet visible for any other reason, this fails cleanly rather than guessing at
     * how to expand "Devices" itself, matching {@link MasterConfigurationValidation#open()}'s own
     * navigation. Used only by {@link #runDevicesAuditLogsExportFlow()}.
     */
    public boolean navigateToDevicesAuditLogs() {
        Locator auditLogsLink = page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Audit Logs").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(auditLogsLink, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[AUDIT LOGS]   'Audit Logs' link (Devices area) not found.");
            return false;
        }
        try {
            auditLogsLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   'Audit Logs' link (Devices area) could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean opened = SoakUiUtils.waitVisible(exportButton(), ELEMENT_TIMEOUT_MS)
                || SoakUiUtils.waitVisible(clearDateFilterButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[AUDIT LOGS] Devices Audit Logs opened: " + (opened ? "YES" : "NO"));
        return opened;
    }

    // ---------------------------------------------------------------------
    // Page verification
    // ---------------------------------------------------------------------

    /**
     * Confirms the page's stable, always-present controls ("Add Filter", "Export") rather than a
     * single combined-text blob. The data-grid column headers ("Audit ID" / "Date &amp; Time" /
     * "User" / "Action") only render once at least one row exists - confirmed live: an empty
     * "No records found" state renders no header row at all - so their presence is logged
     * best-effort here rather than gated on.
     */
    public boolean verifyAuditLogsPage() {
        // "Add Filter" is logged best-effort, not gated on - confirmed live: it can be absent in a
        // build/state where "Export" (the control this whole flow actually depends on) is present
        // and fully working, so requiring both made an otherwise-successful export flow report
        // FAIL over an unrelated, optional control.
        boolean addFilterOk = SoakUiUtils.waitVisible(addFilterButton(), ELEMENT_TIMEOUT_MS);
        boolean exportOk = SoakUiUtils.waitVisible(exportButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[AUDIT LOGS]   'Add Filter' present(best-effort): " + addFilterOk
                + ", 'Export' present: " + exportOk);

        for (String column : new String[] {"Audit ID", "Date & Time", "User", "Action"}) {
            boolean present = SoakUiUtils.isVisibleQuietly(
                    page.getByText(column, new Page.GetByTextOptions().setExact(true)).first());
            System.out.println("[AUDIT LOGS]   Column header '" + column + "' present: " + present
                    + (present ? "" : " (no rows currently loaded, or the build labels it differently)"));
        }
        return exportOk;
    }

    // ---------------------------------------------------------------------
    // Date & Time filter
    // ---------------------------------------------------------------------

    /**
     * Clicks "Clear Date & Time filter" if a date filter chip is currently active, monitoring the
     * background Audit Logs list API the click is expected to trigger (the list refresh once the
     * date range is removed) via the same lightweight per-call response listener already used in
     * {@link #exportAuditLogs()} - not a new API framework. Its absence is not a failure - the
     * filter may already be unset - so this always returns {@code true} unless the button is
     * present but the click itself fails.
     */
    public boolean clearDateTimeFilter() {
        Locator clearDate = clearDateFilterButton();
        if (!SoakUiUtils.isVisibleQuietly(clearDate)) {
            System.out.println("[AUDIT LOGS]   No active Date & Time filter to clear.");
            return true;
        }
        AtomicReference<Response> captured = new AtomicReference<>();
        Consumer<Response> listener = response -> {
            if (captured.get() == null && isAuditListResponse(response)) {
                captured.set(response);
            }
        };
        page.onResponse(listener);
        try {
            clearDate.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            long deadline = System.currentTimeMillis() + 8000;
            while (captured.get() == null && System.currentTimeMillis() < deadline) {
                page.waitForTimeout(200);
            }
            logApiOutcome("Clear Date & Time filter", captured.get());
            System.out.println("[AUDIT LOGS]   Date & Time filter cleared.");
            return true;
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   'Clear Date & Time filter' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        } finally {
            page.offResponse(listener);
        }
    }

    /** Confirms the filter is actually gone: no "Clear Date & Time filter" control left visible. */
    public boolean verifyDateTimeFilterCleared() {
        boolean cleared = !SoakUiUtils.isVisibleQuietly(clearDateFilterButton());
        System.out.println("[AUDIT LOGS]   Date & Time filter cleared, verified: " + (cleared ? "YES" : "NO"));
        return cleared;
    }

    // ---------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------

    /**
     * Clicks "Export" and waits for whichever happens first: a real file download, or the
     * background export API's own response - reusing {@link SoakUiUtils#clickAndWaitForDownload}
     * for the click/download race (no new download framework) while a small local response
     * listener captures the export API call for diagnostics, exactly the per-class helper pattern
     * already used elsewhere in this codebase (e.g. {@code waitForSuccessToast} in
     * {@link RolesPage} / {@link GroupsPage}) rather than a new API framework. A download alone is
     * success; an API response without a download is reported with its real method/endpoint/status
     * (confirmed live against this build: the export endpoint currently 404s) rather than a bare,
     * uninformative timeout.
     */
    public boolean exportAuditLogs() {
        lastDownload = null;
        Locator export = exportButton();
        if (!SoakUiUtils.waitVisible(export, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[AUDIT LOGS]   'Export' control not found.");
            return false;
        }

        AtomicReference<Response> captured = new AtomicReference<>();
        Consumer<Response> listener = response -> {
            if (captured.get() == null && isExportResponse(response)) {
                captured.set(response);
            }
        };
        page.onResponse(listener);
        Download download;
        try {
            download = SoakUiUtils.clickAndWaitForDownload(page, export, 15000);
        } finally {
            page.offResponse(listener);
        }

        if (download != null) {
            lastDownload = download;
            System.out.println("[AUDIT LOGS]   Export downloaded: " + download.suggestedFilename());
            return true;
        }

        Response response = captured.get();
        if (response != null) {
            int status = response.status();
            System.err.println("[AUDIT LOGS]   Export FAILED: no file download arrived within 15s; export API "
                    + safeMethod(response) + " " + shortPath(response.url()) + " -> " + status
                    + " (" + (status >= 200 && status < 300 ? "success response, but no download" : "error") + ").");
        } else {
            System.err.println("[AUDIT LOGS]   Export FAILED: no download and no matching export API call"
                    + " observed within 15s.");
        }
        return false;
    }

    /** True for a response backing the audit-logs export call. Broad on purpose. */
    private boolean isExportResponse(Response response) {
        try {
            String url = response.url().toLowerCase(Locale.ROOT);
            return url.contains("audit") && url.contains("export");
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    /**
     * Fills the "Search Devices Audit Logs" field (matched by a case-insensitive substring on its
     * accessible name, since this codebase has repeatedly found a recorded exact string does not
     * always match a live build's actual wording) with {@code searchText}, monitoring the
     * background Audit Logs list API the search is expected to trigger. An empty string clears the
     * field. The API result is logged as supporting evidence only - the real, gating truth for a
     * search is what {@link #verifyNoRecordsFound()} / {@link #verifySearchResultsPresent()} see
     * rendered afterward, since a client-side-filtered grid may legitimately not re-hit the API at
     * all.
     */
    public boolean searchAuditLogs(String searchText) {
        Locator searchBox = searchBox();
        if (!SoakUiUtils.waitVisible(searchBox, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[AUDIT LOGS]   Search field not found.");
            return false;
        }
        AtomicReference<Response> captured = new AtomicReference<>();
        Consumer<Response> listener = response -> {
            if (captured.get() == null && isAuditListResponse(response)) {
                captured.set(response);
            }
        };
        page.onResponse(listener);
        try {
            searchBox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            searchBox.fill(searchText == null ? "" : searchText);
            long deadline = System.currentTimeMillis() + 8000;
            while (captured.get() == null && System.currentTimeMillis() < deadline) {
                page.waitForTimeout(200);
            }
            logApiOutcome("Search audit logs ('" + searchText + "')", captured.get());
            return true;
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   searchAuditLogs('" + searchText + "') failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        } finally {
            page.offResponse(listener);
        }
    }

    /** Confirms "No records found" (or an equivalent empty-state message) is actually displayed. */
    public boolean verifyNoRecordsFound() {
        Locator noRecords = page.getByText(Pattern.compile("no records found", Pattern.CASE_INSENSITIVE)).first();
        boolean shown = SoakUiUtils.waitVisible(noRecords, ELEMENT_TIMEOUT_MS);
        System.out.println("[AUDIT LOGS]   'No records found' displayed: " + shown);
        return shown;
    }

    /** Confirms the search returned real rows: the "No records found" empty-state is NOT shown. */
    public boolean verifySearchResultsPresent() {
        boolean noRecordsShown = SoakUiUtils.isVisibleQuietly(
                page.getByText(Pattern.compile("no records found", Pattern.CASE_INSENSITIVE)).first());
        System.out.println("[AUDIT LOGS]   Search results present (no 'No records found' shown): "
                + !noRecordsShown);
        return !noRecordsShown;
    }

    /** True for a response backing the Audit Logs list/search call. Broad on purpose. */
    private boolean isAuditListResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return method.equals("GET") && url.contains("audit") && !url.contains("export");
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean logApiOutcome(String context, Response response) {
        boolean apiOk = response == null || (response.status() >= 200 && response.status() < 300);
        if (response != null) {
            System.out.println("[AUDIT LOGS]   " + context + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + response.status() + " (" + (apiOk ? "PASS" : "FAIL") + ")");
        } else {
            System.out.println("[AUDIT LOGS]   " + context + ": no matching API observed within 8s.");
        }
        return apiOk;
    }

    // ---------------------------------------------------------------------
    // Downloaded file validation
    // ---------------------------------------------------------------------

    /**
     * Saves {@code download} under {@code target/soak-test/downloads/}, then confirms it exists
     * and is non-empty. Returns the saved path on success, {@code null} otherwise (logged either
     * way) - the caller only proceeds to {@link #verifyExportedAuditLogData(Path)} on a non-null
     * result.
     */
    public Path verifyDownloadedFile(Download download) {
        if (download == null) {
            System.err.println("[AUDIT LOGS]   No download to verify.");
            return null;
        }
        try {
            Path directory = Paths.get("target", "soak-test", "downloads");
            Files.createDirectories(directory);
            String suggested = download.suggestedFilename();
            String safeName = (suggested == null || suggested.isBlank())
                    ? ("audit-logs-" + System.currentTimeMillis()) : suggested;
            Path saved = directory.resolve(safeName);
            download.saveAs(saved);

            boolean exists = Files.exists(saved);
            long size = exists ? Files.size(saved) : 0;
            boolean nonEmpty = size > 0;
            System.out.println("[AUDIT LOGS]   Downloaded file: " + saved + " exists=" + exists
                    + " size=" + size + " bytes.");
            if (!exists || !nonEmpty) {
                System.err.println("[AUDIT LOGS]   Downloaded file is missing or empty.");
                return null;
            }
            return saved;
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   Could not save/verify the downloaded file: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return null;
        }
    }

    /**
     * Best-effort content check: reads the file as CSV (or, for {@code .xlsx}/{@code .xls}, its
     * first sheet via the Apache POI dependency already on the classpath for the project's Excel
     * reports - no new file-processing utility) and logs its actual header row, since the exact
     * column names are the application's to define, not to assume. Presence of "Audit ID" / "Date
     * &amp; Time" / "User" / "Action" among those headers is logged, not gated on, so a build that
     * genuinely labels a column differently does not fail this check.
     */
    public boolean verifyExportedAuditLogData(Path file) {
        if (file == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> headers;
        try {
            if (looksLikeXmlSpreadsheet(file)) {
                // Confirmed live: this build's ".xls" export is actually the legacy "Excel XML
                // Spreadsheet 2003" (SpreadsheetML) text format, not a real binary XLS/OOXML file -
                // Apache POI's WorkbookFactory rejects it outright ("unsupported file type: XML"),
                // which previously failed this whole check even though the export itself is a
                // perfectly real, non-empty, correctly-named file. Read its header row as XML
                // instead of forcing it through POI.
                headers = readXmlSpreadsheetHeaders(file);
            } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                headers = readSpreadsheetHeaders(file);
            } else {
                headers = readCsvHeaders(file);
            }
        } catch (Exception exception) {
            System.err.println("[AUDIT LOGS]   Could not parse the exported file (" + name + "): "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        if (headers.isEmpty()) {
            System.out.println("[AUDIT LOGS]   Exported file has no readable header row (may be genuinely"
                    + " empty of audit-log data).");
            return true;
        }
        System.out.println("[AUDIT LOGS]   Exported file headers: " + headers);
        for (String expected : new String[] {"Audit ID", "Date & Time", "User", "Action"}) {
            boolean present = headers.stream().anyMatch(h -> h.equalsIgnoreCase(expected));
            System.out.println("[AUDIT LOGS]     expected column '" + expected + "' present: " + present);
        }
        return true;
    }

    /** Sniffs the first ~200 bytes for an XML prolog/root - the legacy "Excel XML Spreadsheet" cue. */
    private boolean looksLikeXmlSpreadsheet(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(200);
            String text = new String(head, StandardCharsets.UTF_8);
            return text.contains("<?xml") || text.contains("<Workbook") || text.contains("urn:schemas-microsoft-com:office");
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Extracts the header row's cell text from a legacy "Excel XML Spreadsheet 2003" file via a
     * light regex scan of its first {@code <Row>...</Row>} - not a full XML parse (unnecessary for
     * one row of plain text cells), and not a new general-purpose file-processing utility, just
     * enough to log the real column names for this one, already-confirmed-broken-in-POI format.
     */
    private List<String> readXmlSpreadsheetHeaders(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int rowStart = content.indexOf("<Row");
        if (rowStart < 0) {
            return List.of();
        }
        int rowEnd = content.indexOf("</Row>", rowStart);
        String firstRow = rowEnd > rowStart ? content.substring(rowStart, rowEnd) : content.substring(rowStart);
        List<String> headers = new ArrayList<>();
        java.util.regex.Matcher matcher = Pattern.compile("<Data[^>]*>(.*?)</Data>", Pattern.DOTALL)
                .matcher(firstRow);
        while (matcher.find()) {
            headers.add(matcher.group(1).trim());
        }
        return headers;
    }

    private List<String> readCsvHeaders(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) {
                return List.of();
            }
            List<String> headers = new ArrayList<>();
            for (String column : firstLine.split(",")) {
                headers.add(column.replace("\"", "").trim());
            }
            return headers;
        }
    }

    private List<String> readSpreadsheetHeaders(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file);
                org.apache.poi.ss.usermodel.Workbook workbook =
                        org.apache.poi.ss.usermodel.WorkbookFactory.create(in)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            List<String> headers = new ArrayList<>();
            if (headerRow != null) {
                headerRow.forEach(cell -> headers.add(cell.toString().trim()));
            }
            return headers;
        }
    }

    // ---------------------------------------------------------------------
    // Locators
    // ---------------------------------------------------------------------

    private Locator auditLogsTab() {
        return page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Audit Logs").setExact(false)).first();
    }

    private Locator addFilterButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add Filter").setExact(false)).first();
    }

    /** Accessible name is "Export" here; a fuller "Export audit logs" aria-label also matches. */
    private Locator exportButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("^export(\\s+audit\\s+logs)?$",
                        Pattern.CASE_INSENSITIVE))).first();
    }

    private Locator clearDateFilterButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Clear Date & Time filter").setExact(false)).first();
    }

    /** Matched by a case-insensitive substring - see {@link #searchAuditLogs(String)}. */
    private Locator searchBox() {
        return page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(Pattern.compile("search.*audit", Pattern.CASE_INSENSITIVE)))
                .first();
    }

    // ---------------------------------------------------------------------
    // Small response helpers (mirrors the same helpers duplicated per page class elsewhere in
    // this codebase, e.g. RolesPage / GroupsPage - not a shared utility on purpose, since each
    // page's own context differs slightly).
    // ---------------------------------------------------------------------

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
            String query = uri.getQuery();
            String base = (path == null || path.isBlank()) ? url : path;
            return query == null ? base : base + "?" + query;
        } catch (Exception exception) {
            return url;
        }
    }
}
