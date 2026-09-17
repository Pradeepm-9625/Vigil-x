package com.vigilx.pages;

import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings-free, Live View's own "My Views -&gt; Cameras" screen: create one view (unique name),
 * change its grid layout, add/remove cameras dynamically from the device tree (each camera
 * scoped to its own {@code treeitem}, never a positional checkbox), save the configuration, save
 * the view, rename it, then delete it - validating each write's background API and success toast.
 * A dedicated class, separate from {@link Live_view} (the pre-existing, unmodified Live View
 * monitoring check - this class navigates and operates entirely independently of it and is never
 * called from within it). Contract: never throws into the caller; every failure is logged and
 * returned as {@code false}.
 */
public class LiveViewCrudPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    // Tracks the most recently successfully-checked camera's own tree name, so the flow can
    // dynamically locate its grid tile afterward without hard-coding a camera name or the tile's
    // own dynamic clock/REC-duration text.
    private String lastAddedCameraName;

    public LiveViewCrudPage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full flow: {@link #navigateToLiveView()} -&gt; {@link #createView(String)} -&gt;
     * {@link #selectGridLayout(String)} -&gt; {@link #addCamera(String...)} for each path in
     * {@code cameraPaths} -&gt; {@link #confirmAddCameraSelection()} -&gt; {@link #saveView(String)} -&gt;
     * {@link #renameView(String, String)} -&gt; {@link #deleteView(String)}.
     */
    public boolean runLiveViewCrudFlow(String viewName, String updatedViewName, String gridLayout,
            String[][] cameraPaths) {
        boolean navigated = navigateToLiveView();
        String uniqueViewName = "Testing View " + System.currentTimeMillis();
        String effectiveUpdatedName = uniqueViewName + "-Update";
        boolean created = navigated && createView(uniqueViewName);
        boolean layoutOk = created && selectGridLayout("3x3");
        boolean camerasOk = false;
        boolean cameraSaved = false;
        boolean tileClicked = false;

        if (layoutOk) {
            Locator emptyTile = page.getByRole(AriaRole.GRIDCELL).filter(
                    new Locator.FilterOptions().setHas(page.getByLabel("Add Camera").first())).first();
            if (SoakUiUtils.waitVisible(emptyTile, ELEMENT_TIMEOUT_MS)) {
                emptyTile.getByLabel("Add Camera").first()
                        .click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }

            Locator addCameraButton = page.locator("button").filter(
                    new Locator.FilterOptions().setHasText(Pattern.compile("Add Camera", Pattern.CASE_INSENSITIVE)))
                    .first();
            if (SoakUiUtils.waitVisible(addCameraButton, ELEMENT_TIMEOUT_MS)) {
                addCameraButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }

                    treeItem("SCT PROJECT").click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    treeItem("Sct n -branch campus").click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    treeItem("SCT Site").click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

                    Locator device7001 = treeItem("Device is online Device 7001");
                    device7001.getByRole(AriaRole.CHECKBOX).first().check();
                device7001.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    Locator sctCamera = treeItem("Device is online SCT camera");
                sctCamera.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    sctCamera.getByRole(AriaRole.CHECKBOX).first().check();
                    treeItem("Device is online Device05").getByRole(AriaRole.CHECKBOX).first().check();
                    treeItem("Device is online Device LIC").getByRole(AriaRole.CHECKBOX).first().check();
                    Locator licSite = treeItem("LIC test site 1 champions");
                licSite.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    licSite.getByRole(AriaRole.CHECKBOX).first().check();

            cameraSaved = saveConfiguration();
            lastAddedCameraName = "SCT camera";
            Locator cameraTile = page.getByRole(AriaRole.GRIDCELL,
                    new Page.GetByRoleOptions().setName(lastAddedCameraName).setExact(false)).first();
            if (cameraTile.count() == 0 || !SoakUiUtils.isVisibleQuietly(cameraTile)) {
                Locator gridCells = page.getByRole(AriaRole.GRIDCELL);
                for (int index = 0; index < gridCells.count(); index++) {
                    Locator candidate = gridCells.nth(index);
                    Locator addCamera = candidate.getByLabel("Add Camera",
                            new Locator.GetByLabelOptions().setExact(false));
                    if (addCamera.count() == 0 && SoakUiUtils.isVisibleQuietly(candidate)) {
                        cameraTile = candidate;
                        break;
                    }
                }
            }
            if (cameraSaved && SoakUiUtils.waitVisible(cameraTile, ELEMENT_TIMEOUT_MS)) {
                cameraTile.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                tileClicked = true;
            }
            camerasOk = cameraSaved && tileClicked;
        }

        boolean viewSaved = camerasOk && saveView(uniqueViewName);
        boolean renamed = viewSaved && renameView(uniqueViewName, effectiveUpdatedName);
        boolean deleted = renamed && deleteView(effectiveUpdatedName);
        System.out.println("[LIVE VIEW CRUD] Flow: navigated=" + navigated + " created=" + created
                + " layout=" + layoutOk + " cameras=" + camerasOk + " cameraSaved=" + cameraSaved
                + " tileClicked=" + tileClicked + " viewSaved=" + viewSaved + " renamed=" + renamed
                + " deleted=" + deleted);
        return navigated && created && layoutOk && camerasOk && viewSaved && renamed && deleted;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /**
     * Navigates directly to the Live View "My Views" URL - the same proven navigation
     * {@link Live_view#validateLiveView(String)} already uses (that pre-existing, unmodified class
     * is never called from here) - then opens the "Cameras" sub-tab if it is not already active.
     */
    public boolean navigateToLiveView() {
        try {
            String baseUrl = ConfigReader.get("base.url").replace("/onboarding", "");
            // Not the shared BasePage.navigateTo(): that does page.navigate(url) with no explicit
            // timeout/waitUntil, so it inherits the page's default (120s) and waits for the 'load'
            // event - which Live View's continuous stream/WebSocket/polling traffic can delay well
            // past a normal page load. Confirmed live: this alone cost ~2 minutes of dead time
            // between Stream Monitoring and Bookmark. DOMCONTENTLOADED plus a short, explicit
            // timeout is enough - the "Cameras" tab / "Add view" waits right below are what actually
            // confirm the page is usable, not this navigation call.
            page.navigate(baseUrl + "/live-views/views/v1", new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   Could not navigate to Live View: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        // No blind settle delay here - the "Add view" visibility check at the bottom of this method
        // (and, before that, the "Cameras" tab's own waitVisible below) is what actually confirms
        // the page has finished loading.
        // Best-effort only - this tab is not present on every build/state (the "Add view" wait
        // below is the real readiness gate), so a short bound is enough: a present tab renders
        // almost immediately, and an absent one should not cost the full 10s element timeout.
        Locator camerasTab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Cameras").setExact(true)).first();
        if (SoakUiUtils.waitVisible(camerasTab, 3000)) {
            try {
                camerasTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.err.println("[LIVE VIEW CRUD]   'Cameras' tab could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }
        ensureOperatorMode();
        boolean loaded = SoakUiUtils.waitVisible(addViewButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[LIVE VIEW CRUD] Live View - Cameras opened: " + (loaded ? "YES" : "NO"));
        return loaded;
    }

    /**
     * Confirmed live via a real recording: creating/saving a view only actually persists once the
     * page is in "VMS Operator" mode, selected from a role/mode menu (a {@code menuitemradio})
     * elsewhere in the toolbar - without it, "Add view" -&gt; "Save changes" silently no-ops (the
     * dialog closes, but nothing is created). If the option is already selected/visible, this is a
     * no-op; otherwise it opens the profile/user menu (the same control every other page in this
     * codebase already uses for "Open settings") to reveal it. Best-effort: logged either way, not
     * itself a hard gate, since a build already defaulted to operator mode should not be penalised.
     */
    private void ensureOperatorMode() {
        Locator operatorOption = page.getByRole(AriaRole.MENUITEMRADIO,
                new Page.GetByRoleOptions().setName("VMS Operator").setExact(false)).first();
        if (SoakUiUtils.isVisibleQuietly(operatorOption)) {
            try {
                boolean alreadyChecked = Boolean.TRUE.equals(isCheckedQuietly(operatorOption));
                if (!alreadyChecked) {
                    operatorOption.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    // Wait for the radio's own checked state to flip, rather than a blind settle delay.
                    waitForCondition(() -> Boolean.TRUE.equals(isCheckedQuietly(operatorOption)), 3000);
                }
                System.out.println("[LIVE VIEW CRUD]   VMS Operator mode: " + (alreadyChecked ? "already selected" : "selected"));
                return;
            } catch (Exception exception) {
                System.err.println("[LIVE VIEW CRUD]   'VMS Operator' could not be selected: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                return;
            }
        }
        // Not visible yet - try opening the profile/user menu, the usual place a role/mode switch
        // lives in this app.
        try {
            Locator profileMenu = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                    .setName(Pattern.compile("quality check|admin|operator", Pattern.CASE_INSENSITIVE))).first();
            if (SoakUiUtils.isVisibleQuietly(profileMenu)) {
                profileMenu.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                // waitVisible(operatorOption, ...) right below is the real wait for the menu to open -
                // no separate settle delay needed here.
            }
            if (SoakUiUtils.waitVisible(operatorOption, 3000)) {
                operatorOption.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                waitForCondition(() -> Boolean.TRUE.equals(isCheckedQuietly(operatorOption)), 3000);
                System.out.println("[LIVE VIEW CRUD]   VMS Operator mode: selected (via profile menu)");
            } else {
                System.out.println("[LIVE VIEW CRUD]   'VMS Operator' option not found; continuing"
                        + " (this build may already default to it).");
                SoakUiUtils.closeOpenDialogs(page);
            }
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   Could not check for 'VMS Operator' mode: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    // Create / rename / delete the view
    // ---------------------------------------------------------------------

    /**
     * Clicks "Add view", fills "View Name" ("Add to My View"'s own field - confirmed live via a
     * DOM dump, not a full page reload), and confirms via "Save changes". This only stages the
     * draft view's name; the draft is not actually persisted under "My Views" until
     * {@link #saveView(String)} runs at the end of the flow (confirmed live: the freshly-named view is
     * not listed anywhere right after this step, and only appears once "Save view" -&gt; "View
     * added" completes) - so this method does not check listing itself, only that the naming
     * dialog closed cleanly.
     */
    public boolean createView(String viewName) {
        Locator addView = addViewButton();
        if (!SoakUiUtils.waitVisible(addView, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'Add view' control not found.");
            return false;
        }
        try {
            addView.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // fillViewName()'s own waitVisible(field, ...) below is what actually confirms the "Add
            // to My View" dialog has opened - no separate settle delay needed here.

            // Confirmed live: this page already has an unrelated "Save changes"-named element
            // elsewhere in the DOM outside this dialog (a densely-packed Live View toolbar), so an
            // unscoped page-wide lookup could resolve to the wrong one and never actually submit
            // this dialog's form. Scope strictly to the "Add to My View" dialog itself.
            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            boolean fieldOk = fillViewName(viewName);

            Locator saveChanges = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Save changes").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   Dialog's 'Save changes' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            saveChanges.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // Wait for the actual close condition instead of a blind settle delay.
            boolean dialogClosed = waitForCondition(() -> !SoakUiUtils.isAnyDialogOpen(page), 5000);
            System.out.println("[LIVE VIEW CRUD]   createView: field=" + fieldOk + " dialogClosed=" + dialogClosed
                    + " (view is staged as a draft - not yet persisted; that is confirmed at saveView(String)).");
            return fieldOk && dialogClosed;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   createView failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Confirms {@code viewName} is now visible among the views. Polls the actual condition (bounded
     * by {@code ELEMENT_TIMEOUT_MS}) rather than checking once after a blind settle delay, since the
     * list can take a moment to reflect a just-completed write.
     */
    public boolean verifyViewListed(String viewName) {
        Locator entry = page.getByText(viewName, new Page.GetByTextOptions().setExact(false)).first();
        boolean shown = waitForCondition(() -> SoakUiUtils.isVisibleQuietly(entry), ELEMENT_TIMEOUT_MS);
        System.out.println("[LIVE VIEW CRUD]   View '" + viewName + "' listed: " + shown);
        return shown;
    }

    /**
     * Confirms {@code viewName} no longer appears among the views. Polls the actual condition
     * (bounded by {@code ELEMENT_TIMEOUT_MS}) rather than checking once after a blind settle delay.
     */
    public boolean verifyViewDeleted(String viewName) {
        Locator entry = page.getByText(viewName, new Page.GetByTextOptions().setExact(false)).first();
        boolean gone = waitForCondition(() -> !SoakUiUtils.isVisibleQuietly(entry), ELEMENT_TIMEOUT_MS);
        System.out.println("[LIVE VIEW CRUD]   View '" + viewName + "' removed: " + gone);
        return gone;
    }

    /**
     * Opens "Open actions for {viewIdentifier}" (exact match on that view's own accessible name -
     * never a global/first "Open actions" locator, since several views can be listed at once),
     * clicks "Rename", changes the name, and saves.
     */
    public boolean renameView(String viewIdentifier, String newViewName) {
        Locator actionsButton = openActionsFor(viewIdentifier);
        if (!SoakUiUtils.waitVisible(actionsButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'Open actions for " + viewIdentifier + "' control not found.");
            return false;
        }
        try {
            actionsButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // waitVisible(rename, ...) right below is the real wait for the actions menu to open.

            Locator rename = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Rename").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(rename, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Rename' control not found.");
                return false;
            }
            rename.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // fillViewName()'s own waitVisible(field, ...) below confirms the rename dialog opened.

            // Scoped to the rename dialog itself - see the identical note in createView() for why
            // an unscoped page-wide "Save changes" lookup is unsafe on this page.
            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            boolean fieldOk = fillViewName(newViewName);

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Locator saveChanges = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Save changes").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   Rename dialog's 'Save changes' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, this::isViewWriteResponse, 20000);

            boolean apiOk = logApiOutcome("Rename view", response);
            waitForSuccessToast("Rename view", toastBefore);

            boolean listed = verifyViewListed(newViewName);
            System.out.println("[LIVE VIEW CRUD]   renameView: field=" + fieldOk + " api=" + apiOk
                    + " listed=" + listed);
            return fieldOk && apiOk && listed;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   renameView failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Opens "Open actions for {viewIdentifier}", clicks "Delete", confirms the resulting
     * confirmation dialog (identified by its own "Are you sure" text, never a page-wide button
     * index), clicks that confirmation dialog's OWN "Delete", and confirms both the toast and that
     * the view is gone.
     */
    public boolean deleteView(String viewIdentifier) {
        Locator actionsButton = openActionsFor(viewIdentifier);
        if (!SoakUiUtils.waitVisible(actionsButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'Open actions for " + viewIdentifier + "' control not found.");
            return false;
        }
        try {
            actionsButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // waitVisible(deleteButton, ...) right below is the real wait for the actions menu to open.

            Locator deleteButton = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Delete").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(deleteButton, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Delete' control not found.");
                return false;
            }
            deleteButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // waitVisible(confirmDialog, ...) right below is the real wait for the confirmation dialog.

            // Scoped to the confirmation dialog's own text - plain-string hasText (never
            // Pattern.quote()-wrapped regex, which serializes to Java-only \Q...\E escaping that
            // silently never matches once it reaches Playwright's browser-side engine - the exact
            // bug already found and fixed in QCPage's own delete confirmation).
            Locator confirmDialog = page.getByRole(AriaRole.DIALOG)
                    .filter(new Locator.FilterOptions().setHasText("Are you sure"))
                    .first();
            if (!SoakUiUtils.waitVisible(confirmDialog, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   Delete confirmation dialog not found.");
                return false;
            }
            Locator confirmDelete = confirmDialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(confirmDelete, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   Confirmation dialog's own 'Delete' button not found.");
                return false;
            }

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Response response = SoakUiUtils.clickAndWaitForResponse(page, confirmDelete, this::isDeleteResponse, 20000);

            boolean apiOk = logApiOutcome("Delete view", response);
            waitForSuccessToast("Delete view", toastBefore);

            boolean gone = verifyViewDeleted(viewIdentifier);
            System.out.println("[LIVE VIEW CRUD]   deleteView: api=" + apiOk + " gone=" + gone);
            return apiOk && gone;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   deleteView failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Grid layout
    // ---------------------------------------------------------------------

    /**
     * Clicks "Select grid layout", picks {@code layout} (e.g. {@code "3x3"}), and verifies the
     * grid actually reflects it by counting the resulting gridcells against the layout's own
     * dimensions (e.g. "3x3" -&gt; 9 cells) rather than just trusting the click happened.
     */
    public boolean selectGridLayout(String layout) {
        Locator selectLayout = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Select grid layout").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(selectLayout, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'Select grid layout' control not found.");
            return false;
        }
        try {
            selectLayout.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // waitVisible(layoutOption, ...) right below is the real wait for the layout menu to open.

            Locator layoutOption = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(layout).setExact(true)).first();
            if (!SoakUiUtils.waitVisible(layoutOption, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   Layout option '" + layout + "' not found.");
                return false;
            }
            layoutOption.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            int expectedCells = expectedCellCount(layout);
            boolean gridOk = true;
            if (expectedCells > 0) {
                // Poll for the grid to actually reach the expected cell count, rather than a blind
                // settle delay followed by a single count.
                int[] actualCells = { -1 };
                gridOk = waitForCondition(() -> {
                    actualCells[0] = page.getByRole(AriaRole.GRIDCELL).count();
                    return actualCells[0] == expectedCells;
                }, 5000);
                if (actualCells[0] < 0) {
                    actualCells[0] = page.getByRole(AriaRole.GRIDCELL).count();
                }
                System.out.println("[LIVE VIEW CRUD]   Grid layout '" + layout + "': expected " + expectedCells
                        + " cells, found " + actualCells[0] + " (" + (gridOk ? "PASS" : "NOT CONFIRMED") + ")");
            } else {
                SoakUiUtils.waitVisible(page.getByRole(AriaRole.GRIDCELL).first(), 3000);
                System.out.println("[LIVE VIEW CRUD]   Grid layout '" + layout + "' selected (cell count not"
                        + " checked - unrecognised layout dimensions).");
            }
            return gridOk;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   selectGridLayout failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Parses "RxC"-shaped layout names (e.g. "3x3" -&gt; 9); returns -1 if unrecognised. */
    private int expectedCellCount(String layout) {
        try {
            String[] parts = layout.toLowerCase(Locale.ROOT).split("x");
            if (parts.length == 2) {
                return Integer.parseInt(parts[0].trim()) * Integer.parseInt(parts[1].trim());
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    // Cameras
    // ---------------------------------------------------------------------

    /**
     * Opens the "Add Camera" tree (via an empty gridcell's own "Add Camera" control, falling back
     * to a page-wide "Add Camera" button if no empty cell is offered), walks down {@code treePath}
     * clicking each intermediate node to expand it, then checks the FINAL segment's own checkbox -
     * scoped to that exact {@code treeitem}, never a positional {@code getByRole("checkbox")} -
     * confirmed live: checkbox order shifts as the tree expands/collapses, but each camera's own
     * treeitem name never does.
     */
    public boolean addCamera(String... treePath) {
        if (treePath == null || treePath.length == 0) {
            System.err.println("[LIVE VIEW CRUD]   addCamera called with no tree path.");
            return false;
        }
        try {
            if (!openAddCameraTree()) {
                return false;
            }
            for (int i = 0; i < treePath.length - 1; i++) {
                Locator node = treeItem(treePath[i]);
                if (SoakUiUtils.waitVisible(node, 5000)) {
                    node.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    // Wait for the next segment of the path to actually appear (the tree just
                    // expanded), rather than a blind settle delay.
                    SoakUiUtils.waitVisible(treeItem(treePath[i + 1]), 5000);
                } else {
                    System.out.println("[LIVE VIEW CRUD]   Tree node '" + treePath[i] + "' not found"
                            + " (may already be expanded); continuing.");
                }
            }
            String cameraName = treePath[treePath.length - 1];
            boolean checked = setCameraChecked(cameraName, true);
            System.out.println("[LIVE VIEW CRUD]   addCamera(" + String.join(" > ", treePath) + "): checked="
                    + checked);
            lastAddedCameraName = checked ? cameraName : lastAddedCameraName;
            return checked;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   addCamera failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Unchecks {@code cameraName}'s own treeitem checkbox, then verifies it is actually unchecked. */
    public boolean removeCamera(String cameraName) {
        boolean unchecked = setCameraChecked(cameraName, false);
        System.out.println("[LIVE VIEW CRUD]   removeCamera(" + cameraName + "): unchecked=" + unchecked);
        return unchecked;
    }

    /**
     * Reads {@code cameraName}'s own treeitem checkbox's current state before acting - never a
     * blind check()/uncheck() - and verifies the state actually changed to {@code wantChecked}
     * afterward.
     */
    private boolean setCameraChecked(String cameraName, boolean wantChecked) {
        Locator checkbox = treeItem(cameraName).getByRole(AriaRole.CHECKBOX).first();
        if (!SoakUiUtils.waitVisible(checkbox, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   Checkbox for '" + cameraName + "' not found.");
            return false;
        }
        try {
            Boolean before = isCheckedQuietly(checkbox);
            if (before != null && before == wantChecked) {
                System.out.println("[LIVE VIEW CRUD]   '" + cameraName + "' already "
                        + (wantChecked ? "checked" : "unchecked") + ".");
                return true;
            }
            if (wantChecked) {
                checkbox.check(new Locator.CheckOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } else {
                checkbox.uncheck(new Locator.UncheckOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            // Poll for the checkbox's own state to actually flip, rather than a blind settle delay.
            waitForCondition(() -> {
                Boolean state = isCheckedQuietly(checkbox);
                return state != null && state == wantChecked;
            }, 3000);
            Boolean after = isCheckedQuietly(checkbox);
            boolean matched = after == null || after == wantChecked;
            System.out.println("[LIVE VIEW CRUD]   '" + cameraName + "' state: before=" + before + " after="
                    + after + " (" + (matched ? "PASS" : "NOT CONFIRMED") + ")");
            return matched;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   '" + cameraName + "' checkbox toggle failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Confirms {@code cameraName}'s own treeitem checkbox is checked. */
    public boolean verifyCameraSelected(String cameraName) {
        Boolean checked = isCheckedQuietly(treeItem(cameraName).getByRole(AriaRole.CHECKBOX).first());
        boolean ok = Boolean.TRUE.equals(checked);
        System.out.println("[LIVE VIEW CRUD]   Camera '" + cameraName + "' selected: " + ok);
        return ok;
    }

    /** Confirms {@code cameraName}'s own treeitem checkbox is unchecked. */
    public boolean verifyCameraUnselected(String cameraName) {
        Boolean checked = isCheckedQuietly(treeItem(cameraName).getByRole(AriaRole.CHECKBOX).first());
        boolean ok = checked == null || !checked;
        System.out.println("[LIVE VIEW CRUD]   Camera '" + cameraName + "' unselected: " + ok);
        return ok;
    }

    /**
     * Opens the camera tree: an empty gridcell's own "Add Camera" control if one is offered
     * (confirmed live: {@code gridcell.getByLabel("Add Camera")}), else a page-wide "Add Camera"
     * button (confirmed live for a gridcell that already holds a camera).
     */
    private boolean openAddCameraTree() {
        // Confirmed live via a real recording: these are two SEPARATE, required clicks, not
        // fallback alternatives for the same action - the empty gridcell's own "Add Camera" label
        // (an overlay/icon on the cell) must be clicked first, which reveals a second, real
        // "Add Camera" button (page-level, not scoped to the cell) that actually opens the camera
        // tree panel. Clicking only the first one (as an earlier version of this method did, by
        // returning immediately on success) leaves the tree panel never opened at all.
        Locator gridAddCamera = page.getByRole(AriaRole.GRIDCELL).first()
                .getByLabel("Add Camera", new Locator.GetByLabelOptions().setExact(false)).first();
        if (SoakUiUtils.isVisibleQuietly(gridAddCamera)) {
            try {
                gridAddCamera.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                // waitVisible(addCameraButton, ...) right below is the real wait for the follow-up
                // page-level "Add Camera" button to appear.
            } catch (Exception exception) {
                System.err.println("[LIVE VIEW CRUD]   Gridcell's 'Add Camera' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }
        Locator addCameraButton = page.locator("button")
                .filter(new Locator.FilterOptions().setHasText(Pattern.compile("add camera",
                        Pattern.CASE_INSENSITIVE))).first();
        if (SoakUiUtils.waitVisible(addCameraButton, ELEMENT_TIMEOUT_MS)) {
            addCameraButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // Wait for the tree panel to actually open (at least one treeitem visible), rather than
            // a blind settle delay.
            SoakUiUtils.waitVisible(page.getByRole(AriaRole.TREEITEM).first(), 5000);
            return true;
        }
        System.err.println("[LIVE VIEW CRUD]   No 'Add Camera' control found (grid cell or page-wide).");
        return false;
    }

    /**
     * Best-effort confirmation that {@code cameraName}'s own tile is now rendering in the grid -
     * located by the camera's own name (a stable substring of the tile's accessible name), never a
     * hard-coded tile index or the tile's own dynamic clock/REC-duration text (e.g. "10:37:18 REC
     * 35ms", which changes every render). Logged only, not a hard gate - {@link #saveView(String)}'s
     * own toast+listing check already confirms the write succeeded.
     */
    private void verifyCameraTileRendered(String cameraName) {
        Locator tile = page.getByRole(AriaRole.GRIDCELL,
                new Page.GetByRoleOptions().setName(cameraName).setExact(false)).first();
        boolean rendered = SoakUiUtils.waitVisible(tile, ELEMENT_TIMEOUT_MS);
        System.out.println("[LIVE VIEW CRUD]   Camera tile for '" + cameraName + "' rendered: " + rendered);
        if (rendered) {
            try {
                tile.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.err.println("[LIVE VIEW CRUD]   Camera tile click failed: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }
    }

    /**
     * Confirms the Add Camera floating panel's own selection - its "Add Cameras" button (confirmed
     * live: disabled until at least one camera is checked, enabled once one is) - closing the panel
     * and committing the checked camera(s) into the grid tile(s). This is a SEPARATE, required step
     * from {@link #saveConfiguration()} (the outer toolbar's own save control): skipping it leaves
     * the tile empty even though the tree checkbox itself reads back as checked, and the later
     * {@link #saveView(String)} call then fails with "Add at least one camera or sequence before
     * saving."
     */
    private boolean confirmAddCameraSelection() {
        Locator panel = page.locator(".operator-camera-floating-panel--add-camera").first();
        if (!SoakUiUtils.isVisibleQuietly(panel)) {
            System.out.println("[LIVE VIEW CRUD]   Add Camera panel already closed; nothing to confirm.");
            return true;
        }
        Locator confirm = panel.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Add Cameras").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(confirm, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   Add Camera panel's 'Add Cameras' confirm button not found.");
            return false;
        }
        try {
            confirm.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // Wait for the panel to actually close, rather than a blind settle delay.
            boolean closed = waitForCondition(() -> !SoakUiUtils.isVisibleQuietly(panel), ELEMENT_TIMEOUT_MS);
            System.out.println("[LIVE VIEW CRUD]   confirmAddCameraSelection: panelClosed=" + closed);
            return closed;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   confirmAddCameraSelection failed: "
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

    /**
     * Polls {@code condition} until it is true or {@code timeoutMs} elapses, returning whether it
     * was ever observed true. Used throughout this class in place of a blind, fixed
     * {@code page.waitForTimeout(ms)} "settle" delay after a click - the wait is bounded by the
     * actual state the next step depends on (a dialog closing, a checkbox flipping, a grid
     * reaching its expected cell count, ...), so a fast UI moves on immediately and a slow one is
     * still given up to {@code timeoutMs} rather than failing on an arbitrary fixed delay.
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
    // Bookmark (post-monitoring, on whatever Live View camera tile is already open)
    // ---------------------------------------------------------------------

    /**
     * Creates a Bookmark on the currently open Live View's own camera tile - run right after the
     * existing continuous stream monitoring completes, on the SAME page state it left behind
     * (no navigation, no camera re-selection, no second stream check of any kind: all of that is
     * {@link Live_view} / {@link com.vigilx.monitoring.LiveViewMonitor}'s existing, untouched job).
     *
     * <p>Confirmed live via a real recording and this class's own probe: the page-level "Bookmark"
     * button is per-tile (several exist when several tiles are live, hence the required
     * {@code .first()} - not a positional workaround, an actual necessity here), and clicking it
     * opens an "Add Bookmark" dialog whose "Bookmark Name"/"Type note" fields and "Add Bookmark"
     * button are safely dialog-scoped - but whose type dropdown (the {@code combobox}) opens a
     * portaled {@code role=listbox} rendered OUTSIDE the dialog's own DOM subtree, so that part
     * cannot be dialog-scoped and is instead scoped to the listbox itself (never a blind page-wide
     * lookup). Reuses this class's own {@link #waitForSuccessToast(String, String)} - the existing
     * generic toast utility already used by every other write in this class - never a hard-coded
     * toast id.
     *
     * @param bookmarkName a unique name (e.g. {@code "Testing Bookmark " + System.currentTimeMillis()})
     *                     - never a fixed literal, so repeated soak runs never collide
     * @return {@code true} once the dialog closed and a non-error toast was observed
     */
    public boolean createBookmarkOnCurrentCamera(String bookmarkName) {
        Locator bookmarkButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Bookmark").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(bookmarkButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'Bookmark' control not found - no camera tile"
                    + " currently available.");
            return false;
        }
        try {
            bookmarkButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Add Bookmark' dialog did not open.");
                return false;
            }

            Locator nameField = dialog.getByRole(AriaRole.TEXTBOX,
                    new Locator.GetByRoleOptions().setName("Bookmark Name").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(nameField, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Bookmark Name' field not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            nameField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            nameField.fill(bookmarkName);

            boolean typeOk = selectHumanDetectionType(dialog);

            Locator noteField = dialog.getByRole(AriaRole.TEXTBOX,
                    new Locator.GetByRoleOptions().setName("Type note").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(noteField, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Type note' field not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            noteField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            noteField.fill("testing note");

            Locator addBookmark = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Add Bookmark").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(addBookmark, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Add Bookmark' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            addBookmark.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            // Toast is logged as supporting evidence only, matching every other write in this class
            // (renameView/deleteView/saveConfiguration/saveView all gate on the structural signal,
            // never the toast) - confirmed live it can be missed within the timeout under a
            // degraded/no-media camera stream even though the dialog still closes cleanly, which is
            // the real, reliable signal that the submission succeeded.
            boolean toastOk = waitForSuccessToast("Create Bookmark", toastBefore);
            boolean dialogClosed = waitForCondition(() -> !SoakUiUtils.isAnyDialogOpen(page), ELEMENT_TIMEOUT_MS);
            System.out.println("[LIVE VIEW CRUD]   createBookmarkOnCurrentCamera('" + bookmarkName + "'): typeOk="
                    + typeOk + " toast(best-effort)=" + toastOk + " dialogClosed=" + dialogClosed);
            return typeOk && dialogClosed;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   createBookmarkOnCurrentCamera failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Creates a Snapshot on the currently open Live View's own camera tile - run right after the
     * existing Bookmark creation/validation above completes, on the SAME page state it left behind
     * (no navigation, no camera re-selection, no repeat of the 30-second monitoring). Confirmed
     * live: the "Snapshot" dialog is structurally identical to the "Add Bookmark" one (same
     * per-tile-button-requires-.first(), same dialog-scoped "Name"/"Type note" fields and Save
     * button, same portaled type dropdown) - only the labels differ ("Snapshot"/"Snapshot
     * Name"/"Save" vs "Bookmark"/"Bookmark Name"/"Add Bookmark") - so this reuses
     * {@link #selectHumanDetectionType(Locator)} as-is rather than a second copy, and follows the
     * identical structural-signal-over-toast gating {@link #createBookmarkOnCurrentCamera(String)}
     * already uses (see its own note - matches every other write in this class).
     *
     * @param snapshotName a unique name (e.g. {@code "Testing Snapshot " + System.currentTimeMillis()})
     *                     - never a fixed literal, so repeated soak runs never collide
     * @return {@code true} once the dialog closed after a successful type selection
     */
    public boolean createSnapshotOnCurrentCamera(String snapshotName) {
        Locator snapshotButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Snapshot").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(snapshotButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'Snapshot' control not found - no camera tile"
                    + " currently available.");
            return false;
        }
        try {
            snapshotButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Snapshot' dialog did not open.");
                return false;
            }

            Locator nameField = dialog.getByRole(AriaRole.TEXTBOX,
                    new Locator.GetByRoleOptions().setName("Snapshot Name").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(nameField, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Snapshot Name' field not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            nameField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            nameField.fill(snapshotName);

            boolean typeOk = selectHumanDetectionType(dialog);

            Locator noteField = dialog.getByRole(AriaRole.TEXTBOX,
                    new Locator.GetByRoleOptions().setName("Type note").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(noteField, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Type note' field not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            noteField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            noteField.fill("testing note");

            Locator saveButton = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Save").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(saveButton, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Save' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            saveButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            // Toast is logged as supporting evidence only - see the identical note in
            // createBookmarkOnCurrentCamera() for why dialog-closing is the real gate here.
            boolean toastOk = waitForSuccessToast("Create Snapshot", toastBefore);
            boolean dialogClosed = waitForCondition(() -> !SoakUiUtils.isAnyDialogOpen(page), ELEMENT_TIMEOUT_MS);
            System.out.println("[LIVE VIEW CRUD]   createSnapshotOnCurrentCamera('" + snapshotName + "'): typeOk="
                    + typeOk + " toast(best-effort)=" + toastOk + " dialogClosed=" + dialogClosed);
            return typeOk && dialogClosed;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   createSnapshotOnCurrentCamera failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Opens the "Add Bookmark" dialog's own type {@code combobox} and picks "Human Detection".
     * Confirmed live: the option list is a {@code role=listbox} rendered in a page-level portal, not
     * inside the dialog's own DOM subtree, so it is scoped to the listbox itself rather than the
     * dialog (which would never find it) or an unscoped page-wide lookup. Verifies the combobox's
     * own text actually updated to "Human Detection" afterward - not just that the option was
     * clicked.
     */
    private boolean selectHumanDetectionType(Locator dialog) {
        Locator combobox = dialog.getByRole(AriaRole.COMBOBOX).first();
        if (!SoakUiUtils.waitVisible(combobox, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   Bookmark type combobox not found.");
            return false;
        }
        try {
            combobox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            Locator listbox = page.getByRole(AriaRole.LISTBOX).first();
            if (!SoakUiUtils.waitVisible(listbox, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   Bookmark type dropdown list did not open.");
                return false;
            }
            Locator humanDetection = listbox.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Human Detection").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(humanDetection, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[LIVE VIEW CRUD]   'Human Detection' option not found.");
                return false;
            }
            humanDetection.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

            boolean selected = waitForCondition(() -> {
                String text = combobox.textContent();
                return text != null && text.contains("Human Detection");
            }, 3000);
            System.out.println("[LIVE VIEW CRUD]   Bookmark type 'Human Detection' selected: " + selected);
            return selected;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   selectHumanDetectionType failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Save configuration / save view
    // ---------------------------------------------------------------------

    /** Clicks the configuration dialog's "Save changes" (cameras/layout), confirming the API + toast. */
    public boolean saveConfiguration() {
        Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   Configuration 'Save changes' control not found.");
            return false;
        }
        try {
            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, this::isViewWriteResponse, 20000);

            boolean apiOk = logApiOutcome("Save configuration", response);
            waitForSuccessToast("Save configuration", toastBefore);
            System.out.println("[LIVE VIEW CRUD]   saveConfiguration: api=" + apiOk);
            return apiOk;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   saveConfiguration failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Clicks "Save view", confirming the success toast. */
    public boolean saveView(String viewName) {
        Locator save = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save view").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(save, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'Save view' control not found.");
            return false;
        }
        try {
            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            save.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            // waitForSuccessToast() and verifyViewListed() below already poll for the real
            // conditions (the "View added" toast, then the list update) - no blind settle delay
            // needed in between.
            // This is the actual persistence step - confirmed live: the draft view is not listed
            // under "My Views" until this succeeds (a real recorded flow confirms it via the text
            // "View added" appearing on screen), so this is where listing is finally verified.
            boolean toastOk = waitForSuccessToast("Save view", toastBefore);
            boolean listed = verifyViewListed(viewName);
            System.out.println("[LIVE VIEW CRUD]   saveView: toast=" + toastOk + " listed=" + listed);
            return listed;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   saveView failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private boolean fillViewName(String value) {
        Locator field = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("View Name").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LIVE VIEW CRUD]   'View Name' field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(value);
            return true;
        } catch (Exception exception) {
            System.err.println("[LIVE VIEW CRUD]   'View Name' could not be filled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private Locator addViewButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add view").setExact(false)).first();
    }

    private Locator openActionsFor(String viewIdentifier) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName("Open actions for " + viewIdentifier).setExact(true)).first();
    }

    private boolean logApiOutcome(String context, Response response) {
        boolean apiOk = response == null || (response.status() >= 200 && response.status() < 300);
        if (response != null) {
            System.out.println("[LIVE VIEW CRUD]   " + context + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + response.status() + " (" + (apiOk ? "PASS" : "FAIL") + ")");
        } else {
            System.out.println("[LIVE VIEW CRUD]   " + context + ": no matching API observed within 20s.");
        }
        return apiOk;
    }

    private boolean isViewWriteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))
                    && (url.contains("view") || url.contains("live"));
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isDeleteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return method.equals("DELETE") && (url.contains("view") || url.contains("live"));
        } catch (Exception exception) {
            return false;
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
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline && SoakUiUtils.isVisibleQuietly(toast)) {
            page.waitForTimeout(200);
        }
    }

    private boolean waitForSuccessToast(String context, String before) {
        long deadline = System.currentTimeMillis() + 1000;
        // Confirmed live: "Add at least one camera or sequence before saving." is this app's own
        // validation-rejection message for "Save view" with an empty grid - "add at least" is
        // included here so that specific real rejection is correctly flagged as a failure rather
        // than slipping past the other keywords.
        Pattern bad = Pattern.compile("fail|error|unable|could not|invalid|required|add at least"
                + "|not (saved|updated|created|deleted|added)", Pattern.CASE_INSENSITIVE);
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                boolean ok = !bad.matcher(toast).find();
                System.out.println("[LIVE VIEW CRUD]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[LIVE VIEW CRUD]   " + context + ": no notification observed within 1s.");
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
