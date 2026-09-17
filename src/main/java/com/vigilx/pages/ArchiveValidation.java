package com.vigilx.pages;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.vigilx.utils.SoakUiUtils;

/**
 * Archive/Playback flow: Add Camera -&gt; Camera Filters -&gt; Active -&gt; Close filter -&gt;
 * select one available active camera from the device tree -&gt; Save changes -&gt; open its tile
 * -&gt; check whether it has an available recording/stream.
 *
 * <p>Camera selection is dynamic and retried: confirmed live, this environment's cameras do not
 * all have footage, so a camera with no recording is removed (the existing per-tile "Remove"
 * flow) and a DIFFERENT, never-before-tried camera from the tree is attempted next - tracked in a
 * {@code Set} of attempted names (identified by each tree row's own {@code textContent()}, since
 * this build's rows carry no {@code aria-label}), never the same camera twice, up to
 * {@link #MAX_CAMERA_ATTEMPTS} unique cameras. The camera tile itself is never used to "find
 * another camera" and the grid layout is never changed - only the tree's own checkboxes drive
 * selection, exactly as the original flow already did.
 *
 * <p>Once a camera with an available recording is found, the existing Export creation flow runs:
 * select the archive timeline range, confirm the bookmark selection, fill a unique Export Name
 * and note, submit, and confirm the export dialog closed.
 *
 * <p>No API monitoring, no reporting, no seed machinery beyond the attempted-camera set - kept as
 * close as possible to the original's bare shape. Never throws into the caller; a failure is
 * logged and returned as {@code false}.
 */
public class ArchiveValidation extends BasePage {

    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_CAMERA_ATTEMPTS = 5;

    public ArchiveValidation(Page page) {
        super(page);
    }

    /**
     * Runs the flow: Playback -&gt; (Add Camera -&gt; Camera Filters -&gt; Active -&gt; Close
     * filter -&gt; select one unattempted active camera -&gt; Save changes -&gt; open its tile
     * -&gt; check recording; remove and retry with a different camera if unavailable) up to
     * {@link #MAX_CAMERA_ATTEMPTS} unique cameras -&gt; once a camera with a recording is found,
     * the existing Export creation flow.
     */
    public boolean validateArchive(String baseUrl) {
        try {
            navigateTo(baseUrl + "/live-views/archive");
            waitForPlaybackHeading();

            Set<String> attemptedCameras = new LinkedHashSet<>();
            boolean recordingFound = false;

            for (int attempt = 1; attempt <= MAX_CAMERA_ATTEMPTS; attempt++) {
                // Confirmed live (matches the recorded reference flow exactly): unlike Live View's
                // own Add Camera (a genuine two-step control), an empty "Camera tile" here has only
                // ONE control - its own inline "Add Camera" icon (class
                // "live-view-empty-tile__add-btn") - clicking it directly opens the Add Cameras
                // floating panel. Always the FIRST gridcell: the camera always lands there, and
                // after a removal that same tile becomes empty again - never scan for "any empty
                // tile", which races with the previous remove's floating panel still settling. No
                // re-navigation here or anywhere else in this retry loop - the reference flow never
                // leaves/reloads the Archive page between attempts, it goes straight from a
                // confirmed Remove back to Add Camera on the same page; removeCurrentCamera()'s own
                // retry (below) is what absorbs a slow-to-open Remove dialog instead.
                Locator gridAddCamera = page.getByRole(AriaRole.GRIDCELL).first()
                        .getByLabel("Add Camera", new Locator.GetByLabelOptions().setExact(false)).first();
                if (!SoakUiUtils.waitVisible(gridAddCamera, TIMEOUT_MS)) {
                    System.err.println("[ARCHIVE]   'Add Camera' control not found (attempt " + attempt
                            + "); stopping.");
                    break;
                }
                gridAddCamera.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

                openCameraFilters();
                selectActiveStatus();
                closeCameraFilters();

                String cameraName = selectOneUnattemptedActiveCamera(attemptedCameras);
                if (cameraName == null) {
                    System.err.println("[ARCHIVE]   No unattempted active camera left in the tree"
                            + " (attempt " + attempt + ").");
                    break;
                }
                attemptedCameras.add(cameraName);
                System.out.println("[ARCHIVE]   Attempt " + attempt + "/" + MAX_CAMERA_ATTEMPTS
                        + ": selected camera '" + cameraName + "'");

                Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
                saveChanges.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
                waitForAddCameraPanelClosed();

                Locator tile = page.getByRole(AriaRole.GRIDCELL,
                        new Page.GetByRoleOptions().setName("Camera tile").setExact(false)).first();
                if (!SoakUiUtils.waitVisible(tile, TIMEOUT_MS)) {
                    System.err.println("[ARCHIVE]   Camera tile did not appear for '" + cameraName + "'.");
                    continue;
                }
                tile.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

                // Stream validation, bounded to the allowed maximum of 10 seconds (see
                // hasAvailableRecording()'s own video-visible wait): available -> continue
                // immediately, no additional wait of any kind. Unavailable -> remove this camera
                // immediately and try a different one - no separate "wait 10s more, then recheck"
                // pass; one bounded check per attempt is the whole validation.
                if (hasAvailableRecording()) {
                    System.out.println("[ARCHIVE]   Recording/stream available for '" + cameraName + "'.");
                    recordingFound = true;
                    break;
                }

                System.out.println("[ARCHIVE]   No recording/stream for '" + cameraName + "'; removing and"
                        + " trying a different camera.");
                if (!removeCurrentCamera(tile)) {
                    // No re-navigation fallback here either - see the note above. If the removal
                    // itself could not be confirmed even after its own bounded retries, the tile's
                    // real state cannot be trusted for a further attempt, so the retry sequence
                    // stops rather than reloading the page to force it back to a known state.
                    System.err.println("[ARCHIVE]   Could not confirm camera removal; stopping retry sequence.");
                    break;
                }
            }

            if (!recordingFound) {
                System.err.println("[ARCHIVE] No camera with available recording/stream found after"
                        + " attempting " + attemptedCameras.size() + " different cameras.");
                return false;
            }

            return true;
        } catch (Exception exception) {
            System.err.println("[ARCHIVE] Archive/Playback flow failed: " + exception.getMessage());
            return false;
        }
    }

    private void waitForPlaybackHeading() {
        page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Playback").setExact(true))
                .waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(TIMEOUT_MS));
    }

    // ---------------------------------------------------------------------
    // Camera filters (existing, unchanged)
    // ---------------------------------------------------------------------

    /**
     * Clicks the Add Cameras popup's OWN "Camera filters" trigger - confirmed live via a real
     * recording: the main Archive page has its own, separate "Camera filters" button that stays
     * visible behind the popup, so the first visible match is not reliably the popup's own one.
     * The popup opens on top of (after, in DOM order) the page's own controls, so its trigger is
     * the LAST visible match rather than the first - dynamic, not a fixed index into a list that
     * could grow or shrink.
     */
    private void openCameraFilters() {
        Locator triggers = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Camera filters").setExact(true));
        int count = triggers.count();
        for (int index = count - 1; index >= 0; index--) {
            Locator trigger = triggers.nth(index);
            if (trigger.isVisible()) {
                trigger.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
                return;
            }
        }
    }

    /** Selects "Active" from the native select under the "Filter Status" label. */
    private void selectActiveStatus() {
        Locator select = page.getByText("Filter Status", new Page.GetByTextOptions().setExact(true))
                .locator("xpath=following::select[1]");
        select.selectOption("Active");
    }

    /** Closes the filter panel opened by {@link #openCameraFilters()}. */
    private void closeCameraFilters() {
        Locator close = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close filter").setExact(true)).first();
        if (close.count() > 0) {
            close.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        }
    }

    /**
     * Waits for the "Add Camera" floating panel (its own tree overlay) to actually close after
     * "Save changes" - confirmed live: it does not always close instantly, and leaving it open
     * intercepts pointer events on the tile's own icons (e.g. its "Remove" trigger) right
     * afterward, well past the tile itself already being visible/clickable.
     */
    private void waitForAddCameraPanelClosed() {
        Locator floatingPanel = page.locator(".operator-camera-floating-panel--add-camera").first();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && SoakUiUtils.isVisibleQuietly(floatingPanel)) {
            page.waitForTimeout(200);
        }
    }

    // ---------------------------------------------------------------------
    // Dynamic camera selection
    // ---------------------------------------------------------------------

    /**
     * Expands the device tree just enough to reveal camera rows, then picks the first available
     * active camera NOT already in {@code attemptedCameras} - a real camera row (identified the
     * same way the tree itself labels it, "Device is online ..."), never a site/folder row, a
     * hard-coded name, or a fixed positional index reused blindly across attempts. Each row's own
     * {@code textContent()} is its identifier (confirmed live: {@code aria-label} is {@code null}
     * on every row in this build, and the tree's checkbox-checked state does NOT persist across
     * Add-Camera panel open/close cycles - confirmed live by re-opening the panel after saving a
     * checked camera and finding it unchecked again - so checkbox state can never be used to tell
     * "already attempted" apart from "not yet tried"; only this explicit set can).
     *
     * @return the selected camera's own name (its row's trimmed text), or {@code null} if every
     *         available candidate has already been attempted
     */
    private String selectOneUnattemptedActiveCamera(Set<String> attemptedCameras) {
        Pattern onlineDevice = Pattern.compile("device is online", Pattern.CASE_INSENSITIVE);
        Locator deviceItems = page.getByRole(AriaRole.TREEITEM, new Page.GetByRoleOptions().setName(onlineDevice));

        // The tree renders collapsed at first; reveal nested rows by expanding collapsed nodes
        // until at least one online device row is visible (bounded so a genuinely empty/offline
        // tree does not loop forever).
        for (int attempt = 0; attempt < 5 && deviceItems.count() == 0; attempt++) {
            Locator expanders = page.locator("[id*='mui-tree-view'] [aria-expanded='false']");
            int count = expanders.count();
            if (count == 0) {
                break;
            }
            for (int index = 0; index < count; index++) {
                try {
                    Locator expander = expanders.nth(index);
                    if (expander.isVisible()) {
                        expander.click(new Locator.ClickOptions().setTimeout(3000));
                    }
                } catch (Exception ignored) {
                    // Row may already be expanded or detached mid-loop; try the next one.
                }
            }
        }

        int total = deviceItems.count();
        for (int index = 0; index < total; index++) {
            Locator device = deviceItems.nth(index);
            String name;
            try {
                name = device.textContent();
            } catch (Exception exception) {
                continue;
            }
            String key = name == null ? null : name.trim();
            if (key == null || key.isBlank() || attemptedCameras.contains(key)) {
                continue;
            }

            Locator checkbox = device.getByRole(AriaRole.CHECKBOX);
            try {
                if (!checkbox.isChecked()) {
                    checkbox.check(new Locator.CheckOptions().setTimeout(TIMEOUT_MS));
                }
            } catch (Exception exception) {
                System.err.println("[ARCHIVE]   Could not check camera '" + key + "': "
                        + exception.getMessage());
                continue;
            }
            return key;
        }
        return null;
    }

    /**
     * Confirms whether the currently open camera tile has an available recording/stream, via the
     * application's own real banner ("No recordings or alerts found for the selected camera(s).")
     * - confirmed live to appear exactly when a camera's Playback timeline has no footage for the
     * queried range, and to be absent once real footage loads.
     *
     * <p>The whole check - banner + this video-visible wait - is this camera attempt's entire
     * stream validation, bounded to the maximum 10 seconds the flow allows per attempt: a video
     * that becomes visible earlier returns immediately (no reason to wait longer), and one that
     * never does is given the full allowed budget before being marked unavailable.
     */
    private boolean hasAvailableRecording() {
        Locator noRecordingBanner = page.getByText(
                Pattern.compile("no recordings or alerts", Pattern.CASE_INSENSITIVE)).first();
        if (SoakUiUtils.isVisibleQuietly(noRecordingBanner)) {
            return false;
        }

        Locator video = page.locator("video").last();
        if (!SoakUiUtils.waitVisible(video, 10000)) {
            return false;
        }
        try {
            String source = String.valueOf(video.evaluate(
                    "element => element.currentSrc || element.src || ''"));
            if (source == null || source.isBlank()) {
                return false;
            }
            int readyState = ((Number) video.evaluate("element => element.readyState")).intValue();
            return readyState >= 2;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Removes the camera currently on {@code tile} via the EXISTING per-tile Remove flow -
     * confirmed live via a real recording: the tile's own "Cancel"-named control opens a "Remove
     * stream?" confirmation dialog with "Cancel"/"Remove" buttons; clicking that dialog's own
     * "Remove" completes it. Waits for the tile to actually revert to "Add Camera" before
     * returning, so the next attempt's {@code gridAddCamera} click never races the removal.
     *
     * <p>The trigger click is a plain, non-forced click - matching the confirmed-working recorded
     * reference flow exactly ({@code getByRole('button', {name: 'Cancel'}).click()}). An earlier
     * version forced this click (bypassing Playwright's actionability wait), which is the likely
     * reason the confirmation dialog was intermittently not opening at all: a forced click
     * dispatches immediately regardless of whether the button is truly ready to receive it, where a
     * natural click waits for that automatically - the retry loop below remains as a safety net for
     * any residual timing flakiness, not as the primary mechanism.
     *
     * @return {@code true} once the tile is confirmed back in its empty "Add Camera" state -
     *         {@code false} for any step along the way that could not be confirmed (control not
     *         found, dialog never opened, tile never reverted).
     */
    private boolean removeCurrentCamera(Locator tile) {
        Locator removeTrigger = tile.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Cancel").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(removeTrigger, TIMEOUT_MS)) {
            System.err.println("[ARCHIVE]   Remove control not found on the current tile.");
            return false;
        }

        // Confirmed live via the natural click's own actionability trace: the "Add Camera"
        // floating panel's tree can still be sitting on top of the tile - intercepting pointer
        // events on its "Cancel" control - even after waitForAddCameraPanelClosed() already
        // reports it gone (its own isVisible() check does not always track a still-occupying-
        // space panel) and after an Escape key press (also confirmed live: no effect on this
        // specific panel). A real mouse click on a known-stable, definitely-outside-the-panel
        // element (the page's own "Playback" heading) is this app's own outside-click-to-dismiss
        // affordance for this panel - forced, since the panel may itself still be intercepting
        // that click target's own hit area too.
        waitForAddCameraPanelClosed();
        Locator playbackHeading = page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Playback").setExact(true)).first();
        try {
            playbackHeading.click(new Locator.ClickOptions().setTimeout(3000).setForce(true));
        } catch (Exception ignored) {
            // Best effort - the retry loop below is the real guarantee.
        }
        waitForAddCameraPanelClosed();

        Locator confirmDialog = page.getByRole(AriaRole.DIALOG)
                .filter(new Locator.FilterOptions().setHasText("Remove stream?")).first();
        // Bounded retry: kept as a safety net for residual UI-timing flakiness now that the click
        // itself is natural (see the note above) rather than forced.
        boolean dialogOpened = false;
        for (int clickAttempt = 1; clickAttempt <= 5 && !dialogOpened; clickAttempt++) {
            removeTrigger.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
            dialogOpened = SoakUiUtils.waitVisible(confirmDialog, clickAttempt < 5 ? 4000 : TIMEOUT_MS);
            if (!dialogOpened && clickAttempt < 5) {
                page.waitForTimeout(400);
            }
        }
        if (!dialogOpened) {
            System.err.println("[ARCHIVE]   Remove confirmation dialog did not open.");
            return false;
        }
        Locator confirmRemove = confirmDialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Remove").setExact(true)).first();
        if (SoakUiUtils.waitVisible(confirmRemove, TIMEOUT_MS)) {
            confirmRemove.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        }

        Locator addCameraAgain = page.getByRole(AriaRole.GRIDCELL).first()
                .getByLabel("Add Camera", new Locator.GetByLabelOptions().setExact(false)).first();
        return SoakUiUtils.waitVisible(addCameraAgain, TIMEOUT_MS);
    }

    // ---------------------------------------------------------------------
    // Export creation (existing flow, only the Export Name is made unique)
    // ---------------------------------------------------------------------

    /**
     * Selects the archive timeline range and creates an export - confirmed live via a real
     * recording: a dedicated bookmark-selection-mode toggle in the playback toolbar
     * ({@code archive-control-10}) must be enabled before clicking the timeline's own scale
     * reveals "Confirm bookmark selection"; both clicks are forced since a decorative sibling
     * (the timeline's playback-settings panel / the moving playhead overlay) legitimately overlaps
     * their hit area without actually blocking the real control underneath. The Export Name is
     * generated fresh on every run so repeated soak executions never collide; every other field
     * and control name is exactly what the existing/recorded flow already uses.
     */
    private boolean createExport() {
        try {
            Locator bookmarkModeToggle = page.locator(
                    ".icon-button.dark.archive-playback-controls__button.archive-control-10").first();
            if (!SoakUiUtils.waitVisible(bookmarkModeToggle, TIMEOUT_MS)) {
                System.err.println("[ARCHIVE]   Bookmark-selection toggle not found.");
                return false;
            }
            Locator scaleTop = page.locator(".archive-timeline__scale-top-inner").first();
            if (!SoakUiUtils.waitVisible(scaleTop, TIMEOUT_MS)) {
                System.err.println("[ARCHIVE]   Timeline scale not found.");
                return false;
            }
            Locator confirmSelection = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Confirm bookmark selection").setExact(false)).first();

            // Bounded retry: confirmed live, this exact toggle -> scale-click sequence can
            // occasionally need a second pass before "Confirm bookmark selection" actually appears
            // (the same class of UI-timing flakiness already seen on the tile's own Remove control) -
            // each retry re-toggles the mode off/back on since a stuck "half-selected" range can
            // otherwise persist between attempts.
            boolean confirmVisible = false;
            for (int attempt = 1; attempt <= 3 && !confirmVisible; attempt++) {
                bookmarkModeToggle.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS).setForce(true));
                scaleTop.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS).setForce(true));
                confirmVisible = SoakUiUtils.waitVisible(confirmSelection, attempt < 3 ? 4000 : TIMEOUT_MS);
            }
            if (!confirmVisible) {
                System.err.println("[ARCHIVE]   'Confirm bookmark selection' did not appear.");
                return false;
            }
            confirmSelection.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            if (!SoakUiUtils.waitVisible(dialog, TIMEOUT_MS)) {
                System.err.println("[ARCHIVE]   Export dialog did not open.");
                return false;
            }

            String exportName = "Export Testing " + System.currentTimeMillis();
            Locator nameField = dialog.getByRole(AriaRole.TEXTBOX,
                    new Locator.GetByRoleOptions().setName("Export Name").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(nameField, TIMEOUT_MS)) {
                System.err.println("[ARCHIVE]   'Export Name' field not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            nameField.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
            nameField.fill(exportName);

            Locator noteField = dialog.getByRole(AriaRole.TEXTBOX,
                    new Locator.GetByRoleOptions().setName("Type note").setExact(false)).first();
            if (SoakUiUtils.waitVisible(noteField, TIMEOUT_MS)) {
                noteField.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
                noteField.fill("Testing notes");
            }

            Locator exportSubmit = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Export").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(exportSubmit, TIMEOUT_MS)) {
                System.err.println("[ARCHIVE]   'Export' submit control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }

            String toastBefore = SoakUiUtils.readToastText(page);
            exportSubmit.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

            long deadline = System.currentTimeMillis() + 10000;
            String toastAfter = "";
            while (System.currentTimeMillis() < deadline) {
                String current = SoakUiUtils.readToastText(page);
                if (!current.isBlank() && !current.equals(toastBefore)) {
                    toastAfter = current;
                    break;
                }
                page.waitForTimeout(300);
            }

            boolean dialogClosed = waitForNoDialogOpen();
            System.out.println("[ARCHIVE]   Export created: name='" + exportName + "' toast=\"" + toastAfter
                    + "\" dialogClosed=" + dialogClosed);
            return dialogClosed;
        } catch (Exception exception) {
            System.err.println("[ARCHIVE]   createExport failed: " + exception.getMessage());
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    private boolean waitForNoDialogOpen() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!SoakUiUtils.isAnyDialogOpen(page)) {
                return true;
            }
            page.waitForTimeout(200);
        }
        return !SoakUiUtils.isAnyDialogOpen(page);
    }
}
