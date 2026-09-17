package com.vigilx.pages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.SoakUiUtils;

/**
 * Archive's own "Exports" tab: select one available export record dynamically, open its Preview,
 * confirm the preview video is visible, then download the export and confirm the file actually
 * arrived - reusing {@link SoakUiUtils#clickAndWaitForDownload} (the same download mechanism
 * already used by {@link AuditLogsPage#exportAuditLogs()}), never a second download framework.
 *
 * <p>Reached after {@link EventSearchValidation}'s Event and Bookmark search flows, on the same
 * Archive page - a separate class, never modifying {@link ArchiveValidation} or
 * {@link EventSearchValidation}. Contract: never throws into the caller; every failure is logged,
 * screenshotted, and returned as {@code false} so the soak continues.
 */
public class ArchiveExportValidation extends BasePage {

    private static final int TIMEOUT_MS = 15000;
    private static final Path DOWNLOAD_DIRECTORY = Paths.get(
            ConfigReader.getOrDefault("archive.export.download.directory",
                    "target/soak-test/downloads/archive-export"));

    /** The step name for whichever flow is currently running, so {@link #fail(String)} files its
     * screenshot/log under the right Soak Test folder ("Export/Videos" vs "Export/Snapshots") -
     * set at the top of each public entry point, read by every shared helper's {@code fail(...)}. */
    private String currentStepName = "Archive Export";

    public ArchiveExportValidation(Page page) {
        super(page);
    }

    /**
     * Runs the flow: Archive -&gt; Exports -&gt; select one export record -&gt; Preview -&gt;
     * verify the preview video -&gt; Download -&gt; verify the downloaded file.
     */
    public boolean validateArchiveExport() {
        currentStepName = "Archive Export";
        try {
            // No navigateTo() here: this runs right after EventSearchValidation's Event/Bookmark
            // Search flows, already on the same Archive page - matching the recorded reference flow
            // exactly (Search -> Bookmarks -> Exports, a direct tab-bar click, never a reload back
            // to the Archive page in between). Confirmed live: reloading here reset the Search
            // panel's tab/date-range state each time instead of just switching tabs.
            Locator exportsTab = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Exports").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(exportsTab, TIMEOUT_MS)) {
                fail("'Exports' control not found");
                return false;
            }
            exportsTab.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

            Locator previewControl = firstExportPreviewControl();
            if (previewControl == null) {
                fail("No export record was available to select");
                return false;
            }
            previewControl.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

            if (!validatePreview()) {
                fail("Export preview/video did not become visible");
                return false;
            }

            Download download = downloadExport();
            if (download == null) {
                fail("Export download did not complete");
                return false;
            }

            Path saved = verifyDownloadedFile(download);
            if (saved == null) {
                fail("Downloaded export file is missing, empty, or has no valid extension");
                return false;
            }
            System.out.println("[ARCHIVE EXPORT]   Download verified | file=" + saved);

            if (!closeOpenDialog("Preview")) {
                fail("Preview dialog did not close after download");
                return false;
            }

            if (!runLogsSearchFlow("fgf")) {
                return false;
            }

            if (!deleteFirstRecord("Delete Video")) {
                fail("Export delete flow did not complete");
                return false;
            }

            System.out.println("[ARCHIVE EXPORT] Flow: PASS | file=" + saved);
            return true;
        } catch (Exception exception) {
            fail("Archive Export validation error: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Runs the Snapshots tab's own flow: Snapshots -&gt; select one snapshot -&gt; Preview -&gt;
     * verify the preview image -&gt; Download -&gt; (best-effort) the preview dialog's own "Export
     * Logs" tab -&gt; Close -&gt; the row's own "Logs" action -&gt; invalid search -&gt; "No data
     * available" -&gt; clear search -&gt; verify restored -&gt; Close -&gt; date range filter -&gt;
     * Apply -&gt; verify a filtered result exists -&gt; select it -&gt; Preview -&gt; verify image
     * -&gt; Download (a second, distinct download of the filtered record) -&gt; Delete -&gt;
     * confirm ("Delete snapshot?") -&gt; verify removed.
     *
     * <p>Reuses every shared helper already used by {@link #validateArchiveExport()} (row
     * discovery, dialog closing, download/file verification, Logs search/clear, delete
     * confirmation) - never a duplicated implementation, and never modifying that method or any of
     * its own behavior.
     */
    public boolean validateArchiveSnapshotExport() {
        currentStepName = "Archive Export - Snapshots";
        try {
            // No navigateTo() here either - see the identical note in validateArchiveExport(). This
            // runs right after that method completes (Exports/Videos already open on the same
            // Archive page), so "Exports" is simply clicked again before switching to "Snapshots".
            Locator exportsTab = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Exports").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(exportsTab, TIMEOUT_MS)) {
                fail("'Exports' control not found");
                return false;
            }
            exportsTab.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

            Locator snapshotsTab = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("Snapshots").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(snapshotsTab, TIMEOUT_MS)) {
                fail("'Snapshots' tab not found");
                return false;
            }
            snapshotsTab.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
            if (!isSnapshotsTabActive(snapshotsTab)) {
                fail("'Snapshots' tab did not become active");
                return false;
            }

            Locator previewControl = firstExportPreviewControl();
            if (previewControl == null) {
                fail("No snapshot record was available to select");
                return false;
            }
            previewControl.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

            if (!validateSnapshotPreview()) {
                fail("Snapshot preview image did not become visible");
                return false;
            }

            Download firstDownload = downloadExport();
            if (firstDownload == null) {
                fail("Snapshot download did not complete");
                return false;
            }
            Path firstSaved = verifyDownloadedFile(firstDownload);
            if (firstSaved == null) {
                fail("Downloaded snapshot file is missing, empty, or has no valid extension");
                return false;
            }
            System.out.println("[ARCHIVE EXPORT]   Snapshot download verified | file=" + firstSaved);

            // Best-effort: this dialog's own "Export Logs" tab (a view switch within the SAME
            // preview dialog, not a separate page-level control or dialog) - confirmed live it does
            // not exist in every build, so this is logged, not gated on.
            openExportLogsTabBestEffort();

            if (!closeOpenDialog("Preview")) {
                fail("Preview dialog did not close after download");
                return false;
            }

            if (!runLogsSearchFlow("dg")) {
                return false;
            }

            if (!openSnapshotDateRangeAndApply()) {
                fail("Snapshot date range filter could not be applied");
                return false;
            }

            Locator filteredPreviewControl = firstExportPreviewControl();
            if (filteredPreviewControl == null) {
                fail("No snapshot record was available after applying the date filter");
                return false;
            }
            filteredPreviewControl.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

            if (!validateSnapshotPreview()) {
                fail("Filtered snapshot's preview image did not become visible");
                return false;
            }

            Download secondDownload = downloadExport();
            if (secondDownload == null) {
                fail("Filtered snapshot download did not complete");
                return false;
            }
            Path secondSaved = verifyDownloadedFile(secondDownload);
            if (secondSaved == null) {
                fail("Downloaded filtered snapshot file is missing, empty, or has no valid extension");
                return false;
            }
            System.out.println("[ARCHIVE EXPORT]   Filtered snapshot download verified | file=" + secondSaved);

            if (!closeOpenDialog("Preview")) {
                fail("Preview dialog did not close after the filtered snapshot download");
                return false;
            }

            if (!deleteFirstRecord("Delete snapshot")) {
                fail("Snapshot delete flow did not complete");
                return false;
            }

            System.out.println("[ARCHIVE EXPORT] Snapshot flow: PASS | files=" + firstSaved + ", " + secondSaved);
            return true;
        } catch (Exception exception) {
            fail("Archive Snapshot Export validation error: " + exception.getMessage());
            return false;
        }
    }

    /** Repeats the exact Logs search/clear/close sequence already proven for the Video flow. */
    private boolean runLogsSearchFlow(String invalidSearchTerm) {
        Locator logsControl = firstExportLogsControl();
        if (logsControl == null) {
            fail("No record's 'Logs' control was found");
            return false;
        }
        logsControl.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

        Locator logsDialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(logsDialog, TIMEOUT_MS)) {
            fail("Logs dialog did not open");
            return false;
        }
        Locator logsSearchBox = logsDialog.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions()
                .setName(Pattern.compile("search", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(logsSearchBox, TIMEOUT_MS)) {
            fail("Logs dialog's Search control was not found");
            return false;
        }
        logLogsContentPresence(logsDialog);

        if (!validateInvalidLogSearch(logsDialog, logsSearchBox, invalidSearchTerm)) {
            fail("'No data available' was not shown for an invalid log search");
            return false;
        }
        if (!clearLogSearchAndValidateRestored(logsDialog, logsSearchBox)) {
            fail("Log results were not restored after clearing the search");
            return false;
        }

        if (!closeOpenDialog("Logs")) {
            fail("Logs dialog did not close");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------

    /**
     * Finds the first export row dynamically - identified by owning a "Preview"-labelled control
     * (every real export record has one; a header row or empty state does not) - never a
     * hard-coded export name/date or a positional index into the row list. Shared by every action
     * that needs "the same available export record" (Preview, Logs, Delete).
     */
    private Locator firstExportRow() {
        Locator rows = page.getByRole(AriaRole.ROW);
        int count = rows.count();
        for (int index = 0; index < count; index++) {
            Locator row = rows.nth(index);
            try {
                if (!row.isVisible()) {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            if (rowControl(row, "preview").count() > 0) {
                return row;
            }
        }
        return null;
    }

    /** {@code row}'s own control matching {@code name} - by accessible label first, then button role. */
    private Locator rowControl(Locator row, String name) {
        Pattern pattern = Pattern.compile(name, Pattern.CASE_INSENSITIVE);
        Locator control = row.getByLabel(pattern).first();
        if (control.count() == 0) {
            control = row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(pattern)).first();
        }
        return control;
    }

    /** The first available export row's own "Preview" control. */
    private Locator firstExportPreviewControl() {
        Locator row = firstExportRow();
        return row == null ? null : rowControl(row, "preview");
    }

    /** The first available export row's own "Logs" control. */
    private Locator firstExportLogsControl() {
        Locator row = firstExportRow();
        if (row == null) {
            return null;
        }
        Locator logs = rowControl(row, "logs");
        return logs.count() > 0 ? logs : null;
    }

    // ---------------------------------------------------------------------
    // Preview
    // ---------------------------------------------------------------------

    /**
     * Confirms the preview video became visible. A single click on it is enough to confirm the
     * control is interactive - never a duplicate click on the same element.
     */
    private boolean validatePreview() {
        Locator previewVideo = page.locator(".export-preview__video-el").first();
        if (!SoakUiUtils.waitVisible(previewVideo, TIMEOUT_MS)) {
            return false;
        }
        previewVideo.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        return true;
    }

    /**
     * Confirms a snapshot's own preview image became visible. Confirmed live: unlike the Video
     * tab's dialog, this one has no {@code <video>} element - the preview is an {@code <img>} - and
     * the dialog's own header also carries a small {@code <img alt="Close">} icon, so this takes
     * the LAST image in the dialog (the actual snapshot) rather than the first.
     */
    private boolean validateSnapshotPreview() {
        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        Locator previewImage = dialog.locator("img").last();
        return SoakUiUtils.waitVisible(previewImage, TIMEOUT_MS);
    }

    /** Confirms the "Snapshots" tab is the active one (its own {@code aria-selected}). */
    private boolean isSnapshotsTabActive(Locator snapshotsTab) {
        try {
            String selected = snapshotsTab.getAttribute("aria-selected");
            if (selected != null) {
                return Boolean.parseBoolean(selected);
            }
        } catch (Exception ignored) {
            // Fall through to the visible tab-content heuristic below.
        }
        // Fallback for a build that does not expose aria-selected: the Snapshots-only "Snapshot
        // Name" column header only renders once that tab is actually active.
        return SoakUiUtils.isVisibleQuietly(
                page.getByText("Snapshot Name", new Page.GetByTextOptions().setExact(true)).first());
    }

    /**
     * Best-effort: clicks the currently open preview dialog's own "Export Logs" tab if present -
     * confirmed live, this is a view switch WITHIN the same dialog (not a separate page-level
     * control), and is absent on some records/builds, so this is logged, never gated on.
     */
    private void openExportLogsTabBestEffort() {
        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        Locator exportLogsTab = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Export Logs").setExact(false)).first();
        if (SoakUiUtils.isVisibleQuietly(exportLogsTab)) {
            try {
                exportLogsTab.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
                System.out.println("[ARCHIVE EXPORT]   Preview dialog's 'Export Logs' tab opened.");
            } catch (Exception exception) {
                System.out.println("[ARCHIVE EXPORT]   Could not open the preview dialog's 'Export Logs' tab: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        } else {
            System.out.println("[ARCHIVE EXPORT]   Preview dialog has no 'Export Logs' tab on this record.");
        }
    }

    // ---------------------------------------------------------------------
    // Snapshot date range
    // ---------------------------------------------------------------------

    /**
     * Self-contained copy of {@link EventSearchValidation}'s own proven date-range logic (never a
     * shared cross-class utility, per this codebase's established per-class-duplication
     * convention) - opens the "Date & Time From" calendar and applies a range wide enough to
     * reliably span two different months. Confirmed live (Event/Bookmark Search): a range confined
     * to a single month's grid never enables the calendar's own Apply button, while a genuine
     * cross-month range does. The end date is always today; the start date defaults to 40 days
     * earlier (configurable), which reliably crosses a month boundary regardless of where in the
     * month "today" falls.
     */
    private boolean openSnapshotDateRangeAndApply() {
        Locator selectButtons = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Select").setExact(false));
        if (selectButtons.count() == 0) {
            System.err.println("[ARCHIVE EXPORT]   Snapshot 'Date & Time From' Select control not found.");
            return false;
        }
        selectButtons.first().click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

        int spanDays = Math.max(11, intConfig("event.search.date.range.days", 40));
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(spanDays);

        selectSnapshotDay(startDate);
        selectSnapshotDay(endDate);

        Locator applyButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Apply").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(applyButton, TIMEOUT_MS)) {
            System.err.println("[ARCHIVE EXPORT]   Snapshot calendar 'Apply' control not found.");
            return false;
        }
        if (!applyButton.isEnabled()) {
            System.err.println("[ARCHIVE EXPORT]   Snapshot calendar 'Apply' is disabled - the date range"
                    + " (" + startDate + " to " + endDate + ") was not accepted as a valid range.");
            return false;
        }
        applyButton.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        System.out.println("[ARCHIVE EXPORT]   Snapshot date range applied: " + startDate + " to " + endDate);
        return true;
    }

    /**
     * Clicks {@code date}'s own month heading (required before a day in that grid can actually be
     * selected - confirmed live on Event/Bookmark Search), then that day's gridcell within the
     * matching month grid.
     */
    private void selectSnapshotDay(LocalDate date) {
        String monthName = date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        Locator header = page.getByText(Pattern.compile("^" + monthName + "\\s+" + date.getYear())).first();
        if (header.count() > 0 && header.isVisible()) {
            header.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        }
        Locator grid = page.getByRole(AriaRole.GRID, new Page.GetByRoleOptions().setName(monthName));
        Locator dayCell = grid.getByRole(AriaRole.GRIDCELL,
                new Locator.GetByRoleOptions().setName(String.valueOf(date.getDayOfMonth())).setExact(true)).first();
        dayCell.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
    }

    // ---------------------------------------------------------------------
    // Download
    // ---------------------------------------------------------------------

    /**
     * Confirmed live: the export table's own rows each carry their own "download"-named icon
     * button in the Actions column, so an unscoped page-wide lookup for a "download" accessible
     * name can resolve {@code .first()} to one of THOSE (hidden behind the open Preview dialog)
     * instead of the dialog's own Download button - clicking a background row control while a
     * modal is open silently does nothing. Scoped strictly to the dialog itself. A video export can
     * also take longer than this class's normal {@link #TIMEOUT_MS} just to begin downloading
     * (server-side preparation), so this waits considerably longer for the download event itself.
     */
    private Download downloadExport() {
        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        Locator downloadButton = dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions()
                .setName(Pattern.compile("download", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(downloadButton, TIMEOUT_MS)) {
            System.err.println("[ARCHIVE EXPORT]   'Download' control not found.");
            return null;
        }
        int downloadTimeoutMs = Math.max(TIMEOUT_MS, intConfig("archive.export.download.timeout.ms", 45000));
        return SoakUiUtils.clickAndWaitForDownload(page, downloadButton, downloadTimeoutMs);
    }

    private int intConfig(String key, int fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    /** Saves {@code download}, then confirms it exists, is non-empty, and has a real extension. */
    private Path verifyDownloadedFile(Download download) {
        if (download == null) {
            return null;
        }
        try {
            Files.createDirectories(DOWNLOAD_DIRECTORY);
            String suggested = download.suggestedFilename();
            String safeName = (suggested == null || suggested.isBlank())
                    ? ("archive-export-" + System.currentTimeMillis()) : suggested;
            Path saved = DOWNLOAD_DIRECTORY.resolve(safeName);
            download.saveAs(saved);

            boolean exists = Files.exists(saved);
            long size = exists ? Files.size(saved) : 0;
            int dot = safeName.lastIndexOf('.');
            boolean hasExtension = dot > 0 && dot < safeName.length() - 1;
            System.out.println("[ARCHIVE EXPORT]   Downloaded file: " + saved + " exists=" + exists
                    + " size=" + size + " bytes hasExtension=" + hasExtension);
            if (!exists || size <= 0 || !hasExtension) {
                return null;
            }
            return saved;
        } catch (Exception exception) {
            System.err.println("[ARCHIVE EXPORT]   Could not save/verify the downloaded file: "
                    + exception.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Logs
    // ---------------------------------------------------------------------

    /** Best-effort: logs whether the Logs dialog already shows history rows, without gating on it. */
    private void logLogsContentPresence(Locator dialog) {
        boolean hasContent = dialog.getByRole(AriaRole.ROW).count() > 0
                || dialog.getByRole(AriaRole.LISTITEM).count() > 0
                || dialog.getByRole(AriaRole.CELL).count() > 0;
        System.out.println("[ARCHIVE EXPORT]   Logs dialog content present: " + hasContent);
    }

    /**
     * Enters {@code invalidSearch} (a value expected to match nothing) into the Logs dialog's own
     * Search box and confirms the app's own "No data available" cell appears - a real assertion on
     * the rendered state, not just a click.
     */
    private boolean validateInvalidLogSearch(Locator dialog, Locator searchBox, String invalidSearch) {
        searchBox.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        searchBox.fill(invalidSearch);

        Locator noData = dialog.getByRole(AriaRole.CELL, new Locator.GetByRoleOptions()
                .setName(Pattern.compile("no data available", Pattern.CASE_INSENSITIVE))).first();
        boolean shown = SoakUiUtils.waitVisible(noData, TIMEOUT_MS);
        System.out.println("[ARCHIVE EXPORT]   'No data available' shown for search '" + invalidSearch
                + "': " + shown);
        return shown;
    }

    /**
     * Clears the Search box (a single {@code fill("")} call - never an extra click on the same
     * box) and polls for the "No data available" state to actually disappear, confirming the
     * log/history results were restored rather than just trusting the clear happened.
     */
    private boolean clearLogSearchAndValidateRestored(Locator dialog, Locator searchBox) {
        searchBox.fill("");
        Locator noData = dialog.getByRole(AriaRole.CELL, new Locator.GetByRoleOptions()
                .setName(Pattern.compile("no data available", Pattern.CASE_INSENSITIVE))).first();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!SoakUiUtils.isVisibleQuietly(noData)) {
                System.out.println("[ARCHIVE EXPORT]   Log results restored after clearing the search.");
                return true;
            }
            page.waitForTimeout(200);
        }
        System.err.println("[ARCHIVE EXPORT]   Log results were not restored after clearing the search.");
        return false;
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /**
     * Re-opens the same available record's Preview (Delete lives inside it, per the existing UI),
     * clicks its "Delete", confirms the resulting confirmation dialog by its own
     * {@code confirmationText} (e.g. "Delete Video" or "Delete snapshot" - never a page-wide/
     * positional "second Delete button"), clicks that dialog's OWN "Delete", and confirms both
     * dialogs actually closed afterward.
     */
    private boolean deleteFirstRecord(String confirmationText) {
        Locator previewControl = firstExportPreviewControl();
        if (previewControl == null) {
            System.err.println("[ARCHIVE EXPORT]   No record available to delete.");
            return false;
        }
        previewControl.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

        Locator previewDialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(previewDialog, TIMEOUT_MS)) {
            System.err.println("[ARCHIVE EXPORT]   Preview dialog did not reopen before delete.");
            return false;
        }

        Locator deleteButton = previewDialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Delete").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(deleteButton, TIMEOUT_MS)) {
            System.err.println("[ARCHIVE EXPORT]   'Delete' control not found in preview dialog.");
            return false;
        }
        deleteButton.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

        Locator confirmDialog = page.getByRole(AriaRole.DIALOG)
                .filter(new Locator.FilterOptions().setHasText(confirmationText))
                .first();
        if (!SoakUiUtils.waitVisible(confirmDialog, TIMEOUT_MS)) {
            System.err.println("[ARCHIVE EXPORT]   Delete confirmation dialog not found.");
            return false;
        }
        Locator confirmDelete = confirmDialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Delete").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(confirmDelete, TIMEOUT_MS)) {
            System.err.println("[ARCHIVE EXPORT]   Confirmation dialog's own 'Delete' button not found.");
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        confirmDelete.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

        boolean dialogsClosed = waitForNoDialogOpen();
        long toastDeadline = System.currentTimeMillis() + 8000;
        String toastAfter = "";
        while (System.currentTimeMillis() < toastDeadline) {
            String current = SoakUiUtils.readToastText(page);
            if (!current.isBlank() && !current.equals(toastBefore)) {
                toastAfter = current;
                break;
            }
            page.waitForTimeout(300);
        }
        System.out.println("[ARCHIVE EXPORT]   Delete export: dialogsClosed=" + dialogsClosed
                + " toast=\"" + toastAfter + "\"");
        return dialogsClosed;
    }

    // ---------------------------------------------------------------------
    // Dialog helpers
    // ---------------------------------------------------------------------

    /**
     * Clicks the currently open dialog's "Close dialog" control and confirms no dialog remains
     * open afterward. If none is open already, this is a no-op success rather than a failure.
     */
    private boolean closeOpenDialog(String context) {
        Locator closeButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close dialog").setExact(false)).first();
        if (!SoakUiUtils.isVisibleQuietly(closeButton)) {
            System.out.println("[ARCHIVE EXPORT]   " + context + ": no open dialog to close.");
            return true;
        }
        closeButton.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        boolean closed = waitForNoDialogOpen();
        System.out.println("[ARCHIVE EXPORT]   " + context + " dialog closed: " + closed);
        return closed;
    }

    private boolean waitForNoDialogOpen() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (page.getByRole(AriaRole.DIALOG).count() == 0) {
                return true;
            }
            page.waitForTimeout(200);
        }
        return page.getByRole(AriaRole.DIALOG).count() == 0;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void fail(String reason) {
        String screenshot = ScreenshotUtils.captureFailure(page, currentStepName,
                "SoakHealthCheckTest.runConfiguredHealthCheck", reason, null);
        System.err.println("[ARCHIVE EXPORT]   FAILED: " + reason + " | screenshot=" + screenshot);
    }
}
