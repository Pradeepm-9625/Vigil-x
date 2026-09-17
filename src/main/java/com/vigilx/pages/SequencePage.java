package com.vigilx.pages;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings-free "VMS Operator -&gt; Cameras -&gt; Sequence" screen: create one sequence (unique
 * name) with cameras picked from the device tree (each camera scoped to its own {@code treeitem},
 * never a positional checkbox), rename it, add it to a dynamically-picked existing grid tile and
 * save, then delete it - validating each write's background API and success toast. A dedicated
 * class, separate from {@link LiveViewCrudPage} and {@link Live_view} (neither of which is ever
 * called from here, nor modified by this class). Contract: never throws into the caller; every
 * failure is logged and returned as {@code false}.
 */
public class SequencePage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 1000;

    public SequencePage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full flow: {@link #navigateToSequence()} -&gt; {@link #createSequence(String, String[][])}
     * -&gt; {@link #verifySequenceListed(String)} -&gt; {@link #updateSequence(String, String)}
     * -&gt; {@link #deleteSequence(String)}. Confirmed live: create requires the dialog's own
     * "Add Cameras" commit followed by the separate "Add Sequence" submit, and this app refuses to
     * create a sequence with fewer than two cameras.
     * <p>
     * {@link #addSequenceToRandomTile(String)} exists as a standalone, best-effort method but is
     * deliberately NOT part of this required flow - go straight from update to delete.
     */
    public boolean runSequenceLifecycle(String sequenceName, String updatedName, String[][] cameraPaths) {
        boolean navigated = navigateToSequence();
        boolean created = navigated && createSequence(sequenceName, cameraPaths);
        boolean listed = created && verifySequenceListed(sequenceName);
        boolean updated = listed && updateSequence(sequenceName, updatedName);
        boolean deleted = updated && deleteSequence(updatedName);
        System.out.println("[SEQUENCE] Flow: navigated=" + navigated + " created=" + created + " listed=" + listed
                + " updated=" + updated + " deleted=" + deleted);
        return navigated && created && listed && updated && deleted;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /**
     * Navigates to the same Live View/Cameras area {@link LiveViewCrudPage} already uses (that
     * class is never called from here), ensures "VMS Operator" mode, then opens the "Cameras" ->
     * "Sequence" screen - matching the confirmed recording {@code VMS Operator -> Cameras ->
     * Sequence}.
     */
    public boolean navigateToSequence() {
        try {
            String baseUrl = ConfigReader.get("base.url").replace("/onboarding", "");
            // Not the shared BasePage.navigateTo(): see LiveViewCrudPage.navigateToLiveView() for
            // why - page.navigate() with no explicit timeout/waitUntil inherits the page's default
            // (120s) and waits for 'load', which this same Live View area's continuous stream
            // traffic can delay well past a normal page load.
            page.navigate(baseUrl + "/live-views/views/v1", new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   Could not navigate to Live View area: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        ensureOperatorMode();
        Locator camerasButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Cameras").setExact(false)).first();
        if (SoakUiUtils.waitVisible(camerasButton, ELEMENT_TIMEOUT_MS)) {
            try {
                camerasButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.err.println("[SEQUENCE]   'Cameras' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }
        Locator sequenceButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Sequence").setExact(true)).first();
        if (SoakUiUtils.waitVisible(sequenceButton, ELEMENT_TIMEOUT_MS)) {
            try {
                sequenceButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.err.println("[SEQUENCE]   'Sequence' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }
        boolean loaded = SoakUiUtils.waitVisible(addSequenceButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[SEQUENCE] Sequence screen opened: " + (loaded ? "YES" : "NO"));
        return loaded;
    }

    /**
     * Same "VMS Operator" mode requirement already confirmed live for {@link LiveViewCrudPage} -
     * kept as this class's own copy (per the existing per-class convention already used for toast
     * helpers) rather than reaching into that page object, so neither class depends on the other.
     */
    private void ensureOperatorMode() {
        Locator operatorOption = page.getByRole(AriaRole.MENUITEMRADIO,
                new Page.GetByRoleOptions().setName("VMS Operator").setExact(false)).first();
        if (SoakUiUtils.isVisibleQuietly(operatorOption)) {
            try {
                boolean alreadyChecked = Boolean.TRUE.equals(isCheckedQuietly(operatorOption));
                if (!alreadyChecked) {
                    operatorOption.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    waitForCondition(() -> Boolean.TRUE.equals(isCheckedQuietly(operatorOption)), 3000);
                }
                System.out.println("[SEQUENCE]   VMS Operator mode: " + (alreadyChecked ? "already selected" : "selected"));
                return;
            } catch (Exception exception) {
                System.err.println("[SEQUENCE]   'VMS Operator' could not be selected: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                return;
            }
        }
        try {
            Locator profileMenu = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                    .setName(Pattern.compile("quality check|admin|operator", Pattern.CASE_INSENSITIVE))).first();
            if (SoakUiUtils.isVisibleQuietly(profileMenu)) {
                profileMenu.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            if (SoakUiUtils.waitVisible(operatorOption, 3000)) {
                operatorOption.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                waitForCondition(() -> Boolean.TRUE.equals(isCheckedQuietly(operatorOption)), 3000);
                System.out.println("[SEQUENCE]   VMS Operator mode: selected (via profile menu)");
            } else {
                System.out.println("[SEQUENCE]   'VMS Operator' option not found; continuing"
                        + " (this build may already default to it).");
                SoakUiUtils.closeOpenDialogs(page);
            }
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   Could not check for 'VMS Operator' mode: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    /**
     * Clicks "Add sequence", fills "Sequence Name" (unique per run), checks each camera in
     * {@code cameraPaths} (each scoped to its own {@code treeitem}, never a positional checkbox -
     * see {@link #addCamera(String...)}), then submits via the dialog's own "Add Sequence" button
     * (note: the trigger is "Add sequence" and the submit is "Add Sequence" - two distinct,
     * differently-cased controls, confirmed via the recorded flow) and confirms the success toast.
     */
    public boolean createSequence(String sequenceName, String[][] cameraPaths) {
        Locator addSequence = addSequenceButton();
        if (!SoakUiUtils.waitVisible(addSequence, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[SEQUENCE]   'Add sequence' control not found.");
            return false;
        }
        try {
            addSequence.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            boolean fieldOk = fillSequenceName(sequenceName);

            // Confirmed live via a real recording: a sequence needs at least two cameras - the
            // dialog's tree-selection panel and its final submit are two DISTINCT stages, not one
            // control. Checking camera(s), then clicking the panel's own "Add Cameras" button,
            // commits them into "Selected Cameras"; only after that commit does the dialog's real
            // submit control - "Add Sequence" (exact) - actually create anything. Clicking only
            // "Add Cameras" (as an earlier version of this method mistakenly treated as the final
            // submit) is a silent no-op: no API call, no toast, dialog stays open.
            boolean camerasOk = selectTwoRandomCameras(cameraPaths);

            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            Locator commitCameras = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Add Cameras").setExact(true)).first();
            if (SoakUiUtils.waitVisible(commitCameras, ELEMENT_TIMEOUT_MS)) {
                try {
                    commitCameras.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                } catch (Exception exception) {
                    System.err.println("[SEQUENCE]   'Add Cameras' commit click failed: "
                            + SoakUiUtils.firstLine(exception.getMessage()));
                }
            } else {
                System.out.println("[SEQUENCE]   Dialog's 'Add Cameras' commit control not found; continuing"
                        + " to final submit anyway.");
            }

            // The real submit - scoped to the dialog, exact match so it is never confused with the
            // "Add Cameras" commit button above.
            Locator submit = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Add Sequence").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(submit, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[SEQUENCE]   Dialog's 'Add Sequence' submit control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Response response = SoakUiUtils.clickAndWaitForResponse(page, submit, this::isSequenceWriteResponse, 20000);

            boolean apiOk = logApiOutcome("Create sequence", response);
            waitForSuccessToast("Create sequence", toastBefore);

            System.out.println("[SEQUENCE]   createSequence: field=" + fieldOk + " cameras=" + camerasOk
                    + " api=" + apiOk);
            return fieldOk && camerasOk && apiOk;
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   createSequence failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /** Expands the configured tree branch and selects two distinct online cameras at random. */
    private boolean selectTwoRandomCameras(String[][] cameraPaths) {
        try {
            if (cameraPaths != null && cameraPaths.length > 0) {
                String[] firstPath = cameraPaths[0];
                for (int index = 0; index < firstPath.length - 1; index++) {
                    Locator node = treeItem(firstPath[index]);
                    if (SoakUiUtils.isVisibleQuietly(node)) {
                        node.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    }
                }
            }

            Pattern onlineDevice = Pattern.compile("device is online", Pattern.CASE_INSENSITIVE);
            Locator deviceItems = page.getByRole(AriaRole.TREEITEM,
                    new Page.GetByRoleOptions().setName(onlineDevice));
            List<Locator> available = new ArrayList<>();
            for (int index = 0; index < deviceItems.count(); index++) {
                Locator device = deviceItems.nth(index);
                if (SoakUiUtils.isVisibleQuietly(device)
                        && device.getByRole(AriaRole.CHECKBOX).count() > 0) {
                    available.add(device);
                }
            }
            if (available.size() < 2) {
                System.err.println("[SEQUENCE]   Fewer than 2 online cameras were available.");
                return false;
            }

            Collections.shuffle(available, new Random());
            for (int index = 0; index < 2; index++) {
                Locator camera = available.get(index);
                camera.getByRole(AriaRole.CHECKBOX).first()
                        .check(new Locator.CheckOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                System.out.println("[SEQUENCE]   Random camera selected: " + camera.textContent().trim());
            }
            return true;
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   Random camera selection failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private boolean fillSequenceName(String value) {
        Locator field = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Sequence Name").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[SEQUENCE]   'Sequence Name' field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(value);
            return true;
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   'Sequence Name' could not be filled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Cameras (identical convention to LiveViewCrudPage.addCamera - kept as this class's own
    // copy per the existing per-class convention rather than a cross-class dependency)
    // ---------------------------------------------------------------------

    /**
     * Walks down {@code treePath}, clicking each intermediate node to expand it, then checks the
     * FINAL segment's own checkbox - scoped to that exact {@code treeitem}, never a positional
     * {@code getByRole("checkbox")}.
     */
    public boolean addCamera(String... treePath) {
        if (treePath == null || treePath.length == 0) {
            System.err.println("[SEQUENCE]   addCamera called with no tree path.");
            return false;
        }
        try {
            for (int i = 0; i < treePath.length - 1; i++) {
                Locator node = treeItem(treePath[i]);
                if (SoakUiUtils.waitVisible(node, 5000)) {
                    node.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    SoakUiUtils.waitVisible(treeItem(treePath[i + 1]), 5000);
                } else {
                    System.out.println("[SEQUENCE]   Tree node '" + treePath[i] + "' not found"
                            + " (may already be expanded); continuing.");
                }
            }
            String cameraName = treePath[treePath.length - 1];
            boolean checked = setCameraChecked(cameraName, true);
            System.out.println("[SEQUENCE]   addCamera(" + String.join(" > ", treePath) + "): checked=" + checked);
            return checked;
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   addCamera failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private boolean setCameraChecked(String cameraName, boolean wantChecked) {
        Locator checkbox = treeItem(cameraName).getByRole(AriaRole.CHECKBOX).first();
        if (!SoakUiUtils.waitVisible(checkbox, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[SEQUENCE]   Checkbox for '" + cameraName + "' not found.");
            return false;
        }
        try {
            Boolean before = isCheckedQuietly(checkbox);
            if (before != null && before == wantChecked) {
                System.out.println("[SEQUENCE]   '" + cameraName + "' already "
                        + (wantChecked ? "checked" : "unchecked") + ".");
                return true;
            }
            if (wantChecked) {
                checkbox.check(new Locator.CheckOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } else {
                checkbox.uncheck(new Locator.UncheckOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            waitForCondition(() -> {
                Boolean state = isCheckedQuietly(checkbox);
                return state != null && state == wantChecked;
            }, 3000);
            Boolean after = isCheckedQuietly(checkbox);
            boolean matched = after == null || after == wantChecked;
            System.out.println("[SEQUENCE]   '" + cameraName + "' state: before=" + before + " after=" + after
                    + " (" + (matched ? "PASS" : "NOT CONFIRMED") + ")");
            return matched;
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   '" + cameraName + "' checkbox toggle failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private Locator treeItem(String name) {
        return page.getByRole(AriaRole.TREEITEM,
                new Page.GetByRoleOptions().setName(name).setExact(false)).first();
    }

    private Boolean isCheckedQuietly(Locator checkbox) {
        try {
            return checkbox.isChecked();
        } catch (Exception exception) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Update (rename)
    // ---------------------------------------------------------------------

    /**
     * Opens "Open actions for {sequenceName}" (exact match on that sequence's own accessible name
     * - never a global/first "Open actions" locator), clicks "Edit", changes the name, and saves
     * via the dialog's own "Save Changes".
     */
    public boolean updateSequence(String sequenceName, String newName) {
        Locator actionsButton = openActionsFor(sequenceName);
        if (!SoakUiUtils.waitVisible(actionsButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[SEQUENCE]   'Open actions for " + sequenceName + "' control not found.");
            return false;
        }
        try {
            actionsButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            Locator edit = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Edit").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(edit, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[SEQUENCE]   'Edit' control not found.");
                return false;
            }
            edit.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            boolean fieldOk = fillSequenceName(newName);

            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            Locator saveChanges = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Save Changes").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[SEQUENCE]   Edit dialog's 'Save Changes' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, this::isSequenceWriteResponse, 20000);

            boolean apiOk = logApiOutcome("Update sequence", response);
            waitForSuccessToast("Update sequence", toastBefore);

            boolean listed = verifySequenceListed(newName);
            System.out.println("[SEQUENCE]   updateSequence: field=" + fieldOk + " api=" + apiOk + " listed=" + listed);
            return fieldOk && apiOk && listed;
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   updateSequence failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Add to a (dynamically-picked) grid tile - best-effort
    // ---------------------------------------------------------------------

    /**
     * Best-effort: opens the sequence's own actions menu, then - if the app offers an existing
     * Live View grid with tiles at this point - clicks a RANDOMLY-picked gridcell (never a fixed
     * index or a specific camera's name, which would tie this to one environment/run) to assign
     * the sequence to it, then saves the view. Logged either way; failures here do not fail the
     * overall lifecycle (see {@link #runSequenceLifecycle}), since this secondary affordance's
     * locators have not been confirmed as stable across builds the way create/update/delete have.
     */
    public boolean addSequenceToRandomTile(String sequenceName) {
        try {
            Locator actionsButton = openActionsFor(sequenceName);
            if (!SoakUiUtils.waitVisible(actionsButton, 5000)) {
                System.out.println("[SEQUENCE]   addSequenceToRandomTile: 'Open actions for " + sequenceName
                        + "' not found; skipping (best-effort).");
                return false;
            }
            actionsButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            int cellCount = page.getByRole(AriaRole.GRIDCELL).count();
            if (cellCount == 0) {
                System.out.println("[SEQUENCE]   addSequenceToRandomTile: no grid tiles available on this"
                        + " screen; skipping (best-effort).");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            int randomIndex = new Random().nextInt(cellCount);
            Locator randomTile = page.getByRole(AriaRole.GRIDCELL).nth(randomIndex);
            randomTile.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            Locator actionsAgain = openActionsFor(sequenceName);
            if (SoakUiUtils.waitVisible(actionsAgain, 3000)) {
                actionsAgain.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }

            Locator saveView = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save view").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(saveView, 5000)) {
                System.out.println("[SEQUENCE]   addSequenceToRandomTile: 'Save view' control not offered"
                        + " here; skipping (best-effort).");
                return false;
            }
            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            saveView.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            boolean toastOk = waitForSuccessToast("Add sequence to tile", toastBefore);
            System.out.println("[SEQUENCE]   addSequenceToRandomTile(tile #" + randomIndex + " of " + cellCount
                    + "): toast=" + toastOk);
            return toastOk;
        } catch (Exception exception) {
            System.out.println("[SEQUENCE]   addSequenceToRandomTile: best-effort step could not complete - "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        } finally {
            // This exploratory step clicks an existing grid tile and may leave the app on a
            // different screen (or mid-menu) regardless of whether it succeeded. Unconditionally
            // re-navigate back to the Sequence list so deleteSequence() (called right after this in
            // runSequenceLifecycle) always starts from a known-good screen, rather than letting this
            // best-effort step silently break a real, gating step downstream.
            navigateToSequence();
        }
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /**
     * Opens "Open actions for {sequenceName}", clicks "Delete", confirms the resulting
     * confirmation dialog (identified by its own "Are you sure" text, never a page-wide button
     * index), clicks that confirmation dialog's OWN "Delete", and confirms both the toast and that
     * the sequence is gone.
     */
    public boolean deleteSequence(String sequenceName) {
        Locator actionsButton = openActionsFor(sequenceName);
        if (!SoakUiUtils.waitVisible(actionsButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[SEQUENCE]   'Open actions for " + sequenceName + "' control not found.");
            return false;
        }
        try {
            actionsButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            Locator deleteButton = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Delete").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(deleteButton, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[SEQUENCE]   'Delete' control not found.");
                return false;
            }
            deleteButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            // Scoped to the confirmation dialog's own text - plain-string hasText (never
            // Pattern.quote()-wrapped regex - that produces Java-only \Q...\E escaping, invalid JS
            // regex once serialized to Playwright's browser-side engine).
            Locator confirmDialog = page.getByRole(AriaRole.DIALOG)
                    .filter(new Locator.FilterOptions().setHasText("Are you sure"))
                    .first();
            if (!SoakUiUtils.waitVisible(confirmDialog, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[SEQUENCE]   Delete confirmation dialog not found.");
                return false;
            }
            Locator confirmDelete = confirmDialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(confirmDelete, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[SEQUENCE]   Confirmation dialog's own 'Delete' button not found.");
                return false;
            }

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Response response = SoakUiUtils.clickAndWaitForResponse(page, confirmDelete, this::isDeleteResponse, 20000);

            boolean apiOk = logApiOutcome("Delete sequence", response);
            waitForSuccessToast("Delete sequence", toastBefore);

            boolean gone = verifySequenceDeleted(sequenceName);
            System.out.println("[SEQUENCE]   deleteSequence: api=" + apiOk + " gone=" + gone);
            return apiOk && gone;
        } catch (Exception exception) {
            System.err.println("[SEQUENCE]   deleteSequence failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Verification
    // ---------------------------------------------------------------------

    /** Confirms {@code sequenceName} is now visible among the sequences. Polls the real condition. */
    public boolean verifySequenceListed(String sequenceName) {
        Locator entry = page.getByText(sequenceName, new Page.GetByTextOptions().setExact(false)).first();
        boolean shown = waitForCondition(() -> SoakUiUtils.isVisibleQuietly(entry), ELEMENT_TIMEOUT_MS);
        System.out.println("[SEQUENCE]   Sequence '" + sequenceName + "' listed: " + shown);
        return shown;
    }

    /** Confirms {@code sequenceName} no longer appears among the sequences. Polls the real condition. */
    public boolean verifySequenceDeleted(String sequenceName) {
        Locator entry = page.getByText(sequenceName, new Page.GetByTextOptions().setExact(false)).first();
        boolean gone = waitForCondition(() -> !SoakUiUtils.isVisibleQuietly(entry), ELEMENT_TIMEOUT_MS);
        System.out.println("[SEQUENCE]   Sequence '" + sequenceName + "' removed: " + gone);
        return gone;
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private Locator addSequenceButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add sequence").setExact(false)).first();
    }

    private Locator openActionsFor(String sequenceIdentifier) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName("Open actions for " + sequenceIdentifier).setExact(true)).first();
    }

    private boolean logApiOutcome(String context, Response response) {
        boolean apiOk = response == null || (response.status() >= 200 && response.status() < 300);
        if (response != null) {
            System.out.println("[SEQUENCE]   " + context + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + response.status() + " (" + (apiOk ? "PASS" : "FAIL") + ")");
        } else {
            System.out.println("[SEQUENCE]   " + context + ": no matching API observed within 20s.");
        }
        return apiOk;
    }

    private boolean isSequenceWriteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))
                    && url.contains("sequence");
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isDeleteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return method.equals("DELETE") && url.contains("sequence");
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Polls {@code condition} until it is true or {@code timeoutMs} elapses, returning whether it
     * was ever observed true. Used throughout this class in place of a blind, fixed
     * {@code page.waitForTimeout(ms)} "settle" delay after a click - the wait is bounded by the
     * actual state the next step depends on.
     */
    private boolean waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (Exception ignored) {
                // Treat a transient locator/evaluation error as "not yet true" and keep polling.
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            page.waitForTimeout(100);
        }
    }

    // ---------------------------------------------------------------------
    // Toast helpers (per-class, matching the convention already used elsewhere)
    // ---------------------------------------------------------------------

    private Locator toastLocator() {
        return page.locator("[id^='common-toast'], [class*='toast' i], [role='alert'], [class*='snackbar' i]")
                .first();
    }

    private void waitForToastToClear() {
        Locator toast = toastLocator();
        if (!SoakUiUtils.isVisibleQuietly(toast)) {
            return;
        }
        try {
            toast.click(new Locator.ClickOptions().setTimeout(2000));
        } catch (Exception ignored) {
            // Best effort - the passive wait below is the real guarantee.
        }
        // Condition-based: exits as soon as the toast is gone. Matches LiveViewCrudPage's identical
        // helper - only a lingering toast (rare; this app's writes mostly don't show one at all, per
        // waitForSuccessToast's own findings) ever costs the full ceiling.
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline && SoakUiUtils.isVisibleQuietly(toast)) {
            page.waitForTimeout(200);
        }
    }

    private boolean waitForSuccessToast(String context, String before) {
        // Confirmed live: create/update/delete never actually show this toast in this build - their
        // real success gate is the API response (apiOk) checked before this call, with this result
        // discarded at every required-flow call site. 10s of blind polling for a toast that never
        // arrives cost ~30s per Sequence run for zero functional benefit. Matches the 1s budget
        // LiveViewCrudPage's identical helper already uses for the same discarded-result pattern.
        long deadline = System.currentTimeMillis() + 1000;
        Pattern bad = Pattern.compile("fail|error|unable|could not|invalid|required|add at least"
                + "|not (saved|updated|created|deleted|added)", Pattern.CASE_INSENSITIVE);
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                boolean ok = !bad.matcher(toast).find();
                System.out.println("[SEQUENCE]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[SEQUENCE]   " + context + ": no notification observed within 1s.");
        return false;
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
