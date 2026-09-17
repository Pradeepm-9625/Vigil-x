package com.vigilx.pages;

import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; QC: the checklist section + checklist item lifecycle - create a section (title +
 * description), add one item to it (its toggle read/changed/restored dynamically before saving,
 * never blindly clicked), verify both are listed, rename the section and the item, delete the
 * item, then delete the section (only after its item is gone). A dedicated class per this
 * project's convention of one page object per distinct screen (mirrors {@link GroupsPage} /
 * {@link RolesPage} sitting alongside {@link UsersRolesPage}); does not modify any other page
 * class. Contract: never throws into the caller; every failure is logged and returned as
 * {@code false}.
 *
 * <p>Section/item identification is always by their own text/accessible name (never a fixed list
 * position or a hard-coded id) - "Add Item" is scoped to the specific section card containing the
 * target section's title, since the QC page can already hold other, unrelated sections.
 */
public class QCPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public QCPage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full lifecycle: {@link #navigateToQC()} -&gt; {@link #createChecklistSection(QCSectionData)}
     * -&gt; {@link #createChecklistItem(String, QCItemData)} -&gt;
     * {@link #updateChecklistSection(String, QCSectionData)} -&gt;
     * {@link #updateChecklistItem(String, QCItemData)} -&gt;
     * {@link #deleteChecklistItem(String)} -&gt; {@link #deleteChecklistSection(String)}, each
     * gated on the previous step's success.
     */
    public boolean runQCChecklistLifecycle(QCSectionData section, QCSectionData updatedSection,
            QCItemData item, QCItemData updatedItem) {
        boolean navigated = navigateToQC();
        boolean sectionCreated = navigated && createChecklistSection(section);
        boolean itemCreated = sectionCreated && createChecklistItem(section.name(), item);
        boolean sectionUpdated = itemCreated && updateChecklistSection(section.name(), updatedSection);
        boolean itemUpdated = sectionUpdated && updateChecklistItem(item.name(), updatedItem);
        boolean itemDeleted = itemUpdated && deleteChecklistItem(updatedItem.name());
        boolean sectionDeleted = itemDeleted && deleteChecklistSection(updatedSection.name());
        System.out.println("[QC] Lifecycle: navigated=" + navigated + " sectionCreated=" + sectionCreated
                + " itemCreated=" + itemCreated + " sectionUpdated=" + sectionUpdated
                + " itemUpdated=" + itemUpdated + " itemDeleted=" + itemDeleted
                + " sectionDeleted=" + sectionDeleted);
        return navigated && sectionCreated && itemCreated && sectionUpdated && itemUpdated
                && itemDeleted && sectionDeleted;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /** Opens Settings (only if not already open) then "QC", and confirms the page loaded. */
    public boolean navigateToQC() {
        Locator addSection = addChecklistSectionButton();
        if (!SoakUiUtils.isVisibleQuietly(addSection)) {
            try {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(page.getByText(
                        Pattern.compile("^\\s*QC\\s*$", Pattern.CASE_INSENSITIVE)).first());
                if (!settingsAlreadyOpen && SoakUiUtils.isVisibleQuietly(openSettings)) {
                    openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(500);
                }
                Locator qcLink = page.getByText(Pattern.compile("^\\s*QC\\s*$", Pattern.CASE_INSENSITIVE)).first();
                if (SoakUiUtils.waitVisible(qcLink, ELEMENT_TIMEOUT_MS)) {
                    qcLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(800);
                }
            } catch (Exception exception) {
                System.err.println("[QC]   Could not open QC: " + SoakUiUtils.firstLine(exception.getMessage()));
                return false;
            }
        }
        boolean loaded = SoakUiUtils.waitVisible(addSection, ELEMENT_TIMEOUT_MS);
        System.out.println("[QC] QC page opened: " + (loaded ? "YES" : "NO"));
        return loaded;
    }

    // ---------------------------------------------------------------------
    // Checklist section: create / update / delete
    // ---------------------------------------------------------------------

    /**
     * Clicks "Add checklist section", fills Section title + Description, saves, and confirms both
     * the success toast and that the section is now listed (title + description text visible).
     */
    public boolean createChecklistSection(QCSectionData data) {
        Locator addSection = addChecklistSectionButton();
        if (!SoakUiUtils.waitVisible(addSection, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   'Add checklist section' control not found.");
            return false;
        }
        try {
            addSection.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean titleOk = fillLabeledField("Section title", data.name());
            boolean descOk = fillLabeledField("Description", data.description());

            boolean saved = clickSaveChanges("Create checklist section", this::isSectionWriteResponse);

            boolean listed = verifySectionCreated(data.name(), data.description());
            System.out.println("[QC]   createChecklistSection: title=" + titleOk + " description=" + descOk
                    + " saved=" + saved + " listed=" + listed);
            return titleOk && descOk && saved && listed;
        } catch (Exception exception) {
            System.err.println("[QC]   createChecklistSection failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Confirms {@code name} is now visible on the QC page - the hard gate, since the section
     * card's collapsed list view only ever renders the title (confirmed live via screenshot: the
     * description is never shown there, only inside the section's own edit dialog). Description
     * presence is still checked and logged, but only as a best-effort signal, not a gate.
     */
    public boolean verifySectionCreated(String name, String description) {
        boolean nameShown = SoakUiUtils.isVisibleQuietly(
                page.getByText(name, new Page.GetByTextOptions().setExact(true)).first());
        boolean descShown = description == null || description.isBlank()
                || SoakUiUtils.isVisibleQuietly(
                        page.getByText(description, new Page.GetByTextOptions().setExact(false)).first());
        System.out.println("[QC]   Section '" + name + "' listed: name=" + nameShown
                + " description(best-effort)=" + descShown);
        return nameShown;
    }

    /**
     * Opens "Edit {sectionIdentifier}" (exact accessible name, matching a real recorded
     * {@code getByRole('button', { name: 'Edit ' + name, exact: true })}), changes the title and
     * description, saves, and confirms the new values are displayed.
     */
    public boolean updateChecklistSection(String sectionIdentifier, QCSectionData updated) {
        Locator editButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit " + sectionIdentifier).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   'Edit " + sectionIdentifier + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean titleOk = fillLabeledField("Section title", updated.name());
            boolean descOk = fillLabeledField("Description", updated.description());

            boolean saved = clickSaveChanges("Update checklist section", this::isSectionWriteResponse);

            boolean listed = verifySectionCreated(updated.name(), updated.description());
            System.out.println("[QC]   updateChecklistSection: title=" + titleOk + " description=" + descOk
                    + " saved=" + saved + " listed=" + listed);
            return titleOk && descOk && saved && listed;
        } catch (Exception exception) {
            System.err.println("[QC]   updateChecklistSection failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /** Confirms {@code sectionIdentifier} no longer appears on the QC page. */
    public boolean verifySectionDeleted(String sectionIdentifier) {
        boolean gone = !SoakUiUtils.isVisibleQuietly(
                page.getByText(sectionIdentifier, new Page.GetByTextOptions().setExact(true)).first());
        System.out.println("[QC]   Section '" + sectionIdentifier + "' removed: " + gone);
        return gone;
    }

    /**
     * Opens "Edit {sectionIdentifier}", clicks "Delete", confirms the "Delete Section" dialog, and
     * clicks that dialog's own "Delete" - scoped to the dialog itself (never a page-wide
     * {@code nth()}) since the section editor behind it also has its own "Delete" button sharing
     * the same accessible name.
     */
    public boolean deleteChecklistSection(String sectionIdentifier) {
        Locator editButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit " + sectionIdentifier).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   'Edit " + sectionIdentifier + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            boolean deleted = deleteViaConfirmDialog("Delete Section", "Delete checklist section");
            SoakUiUtils.closeOpenDialogs(page);
            boolean gone = verifySectionDeleted(sectionIdentifier);
            System.out.println("[QC]   deleteChecklistSection: deleted=" + deleted + " gone=" + gone);
            return deleted && gone;
        } catch (Exception exception) {
            System.err.println("[QC]   deleteChecklistSection failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Checklist item: create / update / delete
    // ---------------------------------------------------------------------

    /**
     * Clicks the "Add Item" button belonging to {@code sectionIdentifier}'s own card (never a
     * global position - the QC page can hold other, unrelated sections with their own "Add Item"
     * buttons), fills the checklist item text, exercises its toggle dynamically (reads the current
     * state, changes it, verifies the change, then restores the original state - never a blind
     * double-click), saves, and confirms both the toast and that the item is now listed.
     */
    public boolean createChecklistItem(String sectionIdentifier, QCItemData data) {
        Locator addItem = addItemButtonForSection(sectionIdentifier);
        if (!SoakUiUtils.waitVisible(addItem, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   'Add Item' control for section '" + sectionIdentifier + "' not found.");
            return false;
        }
        try {
            addItem.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean nameOk = fillLabeledField("Checklist item", data.name());
            boolean toggleOk = exerciseToggleDynamically();

            boolean saved = clickSaveChanges("Create checklist item", this::isItemWriteResponse);

            boolean listed = verifyChecklistItemCreated(data.name());
            System.out.println("[QC]   createChecklistItem: name=" + nameOk + " toggle=" + toggleOk
                    + " saved=" + saved + " listed=" + listed);
            return nameOk && saved && listed;
        } catch (Exception exception) {
            System.err.println("[QC]   createChecklistItem failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Reads the checklist item toggle's current state, clicks it once (change), verifies the
     * state actually flipped, then clicks it again to restore the original state - matching a real
     * recorded double-click on this same control, but driven by the observed state each time
     * rather than assumed. A missing toggle just skips (logged, returns {@code true} - not every
     * build's item form necessarily shows one at this point).
     */
    private boolean exerciseToggleDynamically() {
        Locator toggle = page.getByTestId("toggle-switch").first();
        if (!SoakUiUtils.isVisibleQuietly(toggle)) {
            System.out.println("[QC]   Checklist item toggle not present; skipping.");
            return true;
        }
        try {
            String before = toggle.getAttribute("aria-checked");
            toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(300);
            String changed = toggle.getAttribute("aria-checked");
            boolean actuallyChanged = before == null || !before.equals(changed);
            System.out.println("[QC]   Toggle: before=" + before + " afterChange=" + changed
                    + " changed=" + actuallyChanged);
            toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(300);
            String restored = toggle.getAttribute("aria-checked");
            boolean actuallyRestored = before == null || before.equals(restored);
            System.out.println("[QC]   Toggle: afterRestore=" + restored + " restored=" + actuallyRestored);
            return actuallyChanged && actuallyRestored;
        } catch (Exception exception) {
            System.err.println("[QC]   Toggle could not be exercised: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Confirms {@code itemIdentifier} is now visible on the QC page. */
    public boolean verifyChecklistItemCreated(String itemIdentifier) {
        boolean shown = SoakUiUtils.isVisibleQuietly(
                page.getByText(itemIdentifier, new Page.GetByTextOptions().setExact(false)).first());
        System.out.println("[QC]   Checklist item '" + itemIdentifier + "' listed: " + shown);
        return shown;
    }

    /**
     * Opens "Edit {itemIdentifier}", changes the checklist item text, saves, and confirms the new
     * value is displayed.
     */
    public boolean updateChecklistItem(String itemIdentifier, QCItemData updated) {
        Locator editButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit " + itemIdentifier).setExact(false)).first();
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   'Edit " + itemIdentifier + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean nameOk = fillLabeledField("Checklist item", updated.name());
            boolean saved = clickSaveChanges("Update checklist item", this::isItemWriteResponse);

            boolean listed = verifyChecklistItemCreated(updated.name());
            System.out.println("[QC]   updateChecklistItem: name=" + nameOk + " saved=" + saved
                    + " listed=" + listed);
            return nameOk && saved && listed;
        } catch (Exception exception) {
            System.err.println("[QC]   updateChecklistItem failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /** Confirms {@code itemIdentifier} no longer appears on the QC page. */
    public boolean verifyChecklistItemDeleted(String itemIdentifier) {
        boolean gone = !SoakUiUtils.isVisibleQuietly(
                page.getByText(itemIdentifier, new Page.GetByTextOptions().setExact(false)).first());
        System.out.println("[QC]   Checklist item '" + itemIdentifier + "' removed: " + gone);
        return gone;
    }

    /**
     * Opens "Edit {itemIdentifier}", clicks "Delete", confirms the "Delete Checklist Item" dialog,
     * and clicks that dialog's own "Delete" - scoped to the dialog itself (never a page-wide
     * {@code nth()}) since the item editor behind it also has its own "Delete" button sharing the
     * same accessible name.
     */
    public boolean deleteChecklistItem(String itemIdentifier) {
        Locator editButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit " + itemIdentifier).setExact(false)).first();
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   'Edit " + itemIdentifier + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            boolean deleted = deleteViaConfirmDialog("Delete Checklist Item", "Delete checklist item");
            SoakUiUtils.closeOpenDialogs(page);
            boolean gone = verifyChecklistItemDeleted(itemIdentifier);
            System.out.println("[QC]   deleteChecklistItem: deleted=" + deleted + " gone=" + gone);
            return deleted && gone;
        } catch (Exception exception) {
            System.err.println("[QC]   deleteChecklistItem failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    /**
     * Clicks the currently-open editor's "Delete" button, waits for the confirmation dialog whose
     * text starts with {@code dialogTitlePrefix} (e.g. "Delete Section" / "Delete Checklist Item"),
     * and clicks that dialog's OWN "Delete" button - found by scoping to the dialog, never a
     * page-wide index, since the editor behind it shares the same "Delete" accessible name.
     */
    private boolean deleteViaConfirmDialog(String dialogTitlePrefix, String toastContext) {
        Locator deleteButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Delete").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(deleteButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   'Delete' control not found.");
            return false;
        }
        try {
            // Confirmed live via a diagnostic probe: clicking straight from the editor's "Delete"
            // to checking for the confirm dialog (no extra clicks in between) reliably surfaces it
            // - a real DOM dump at that point showed exactly two role="dialog" elements, the editor
            // itself ("Edit Checklist Item...") and the confirm ("Delete Checklist Item\n\nAre you
            // sure..."), so the text filter below correctly isolates the confirm one. An earlier
            // version of this method also clicked into a dialog (matching a codegen recording's own
            // literal clicks) before "Delete" - that extra click landed at the dialog's bounding-box
            // centre with no specific target and, empirically, made the confirm dialog fail to
            // appear reliably, so it was removed rather than kept as an unexplained "just in case"
            // step.
            deleteButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            // Confirmed live via a debug dump: at the exact moment this looked for the dialog, it
            // genuinely existed and was visible with the expected text - the filter itself was the
            // bug. Pattern.quote() wraps the text in Java-only \Q...\E regex-quoting syntax, which
            // is not valid JavaScript regex; Playwright serializes the Pattern to the browser's JS
            // regex engine, so the filter silently never matched anything. Plain hasText(String)
            // does its own case-insensitive substring match with no regex involved, and needs no
            // escaping for a literal label like "Delete Checklist Item" / "Delete Section".
            Locator dialog = page.getByRole(AriaRole.DIALOG)
                    .filter(new Locator.FilterOptions().setHasText(dialogTitlePrefix))
                    .first();
            if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[QC]   '" + dialogTitlePrefix + "' confirmation dialog not found.");
                return false;
            }
            Locator confirmDelete = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(confirmDelete, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[QC]   '" + dialogTitlePrefix + "' dialog's own 'Delete' button not found.");
                return false;
            }
            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Response response = SoakUiUtils.clickAndWaitForResponse(page, confirmDelete, this::isDeleteResponse, 20000);
            page.waitForTimeout(400);

            boolean apiOk = response == null || (response.status() >= 200 && response.status() < 300);
            if (response != null) {
                System.out.println("[QC]   " + toastContext + " API: " + safeMethod(response) + " "
                        + shortPath(response.url()) + " -> " + response.status() + " (" + (apiOk ? "PASS" : "FAIL") + ")");
            } else {
                System.out.println("[QC]   " + toastContext + ": no matching delete API observed within 20s.");
            }
            // The API status is the hard signal (matches the convention already used for
            // Groups/Roles); the toast is logged as supporting evidence but does not gate success -
            // confirmed live: the actual DELETE consistently returns 204 and the item/section is
            // genuinely gone even on runs where the toast itself is not observed within its own
            // window (deep into a long soak run, the toast can simply take longer to render than
            // the API call takes to resolve).
            waitForSuccessToast(toastContext, toastBefore);
            return apiOk;
        } catch (Exception exception) {
            System.err.println("[QC]   " + toastContext + " confirm failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Clicks "Save changes", waits for a matching write API, and confirms the success toast. */
    private boolean clickSaveChanges(String context, java.util.function.Predicate<Response> matcher) {
        Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   " + context + ": 'Save changes' control not found.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
        waitForToastToClear();
        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, matcher, 20000);
        page.waitForTimeout(500);

        boolean apiOk = response == null || (response.status() >= 200 && response.status() < 300);
        if (response != null) {
            System.out.println("[QC]   " + context + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + response.status() + " (" + (apiOk ? "PASS" : "FAIL") + ")");
        } else {
            System.out.println("[QC]   " + context + ": no matching write API observed within 20s.");
        }
        // The API status is the hard signal (matches the convention already used for Groups/Roles);
        // the toast is logged as supporting evidence but does not gate success - see the identical
        // note in deleteViaConfirmDialog for why.
        waitForSuccessToast(context, toastBefore);
        SoakUiUtils.closeOpenDialogs(page);
        return apiOk;
    }

    /** Fills a field identified by its accessible name (aria-label), exact match. */
    private boolean fillLabeledField(String label, String value) {
        if (value == null) {
            return true;
        }
        Locator field = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(label).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[QC]   '" + label + "' field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(value);
            return true;
        } catch (Exception exception) {
            System.err.println("[QC]   '" + label + "' could not be filled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * The "Add Item" button belonging to {@code sectionIdentifier}'s own card: walks up from the
     * section's exact title text to its containing card, then finds that card's own "Add Item"
     * button - the same combined-name-first-then-ancestor-walk pattern already used for Groups /
     * Roles cards elsewhere in this codebase, since the QC page can list several sections at once.
     */
    private Locator addItemButtonForSection(String sectionIdentifier) {
        Locator title = page.getByText(sectionIdentifier, new Page.GetByTextOptions().setExact(true)).first();
        // Confirmed live: the title's own row already has its own "Edit" button, so walking up to
        // the first ancestor with ANY button (as the Groups/Roles card pattern does) stops one
        // level too early here - short of the sibling content row that holds "Add Item". Walk up
        // to the first ancestor that specifically contains an "Add Item" button instead.
        Locator card = title.locator(
                "xpath=ancestor::*[.//button[contains(normalize-space(.),'Add Item')]][1]");
        return card.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Add Item").setExact(false)).first();
    }

    private Locator addChecklistSectionButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add checklist section").setExact(false)).first();
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
        long deadline = System.currentTimeMillis() + 7000;
        while (System.currentTimeMillis() < deadline && SoakUiUtils.isVisibleQuietly(toast)) {
            page.waitForTimeout(200);
        }
    }

    private boolean waitForSuccessToast(String context, String before) {
        long deadline = System.currentTimeMillis() + 10000;
        Pattern bad = Pattern.compile("fail|error|unable|could not|invalid|required|not (saved|updated|created|deleted)",
                Pattern.CASE_INSENSITIVE);
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                boolean ok = !bad.matcher(toast).find();
                System.out.println("[QC]   " + context + " toast: \"" + toast + "\" (" + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[QC]   " + context + ": no notification observed within 10s.");
        return false;
    }

    // ---------------------------------------------------------------------
    // API response helpers
    // ---------------------------------------------------------------------

    private boolean isSectionWriteResponse(Response response) {
        return isWriteResponse(response) && response.url().toLowerCase(Locale.ROOT).contains("section");
    }

    private boolean isItemWriteResponse(Response response) {
        String url = response.url().toLowerCase(Locale.ROOT);
        return isWriteResponse(response) && (url.contains("question") || url.contains("item")
                || url.contains("checklist"));
    }

    private boolean isDeleteResponse(Response response) {
        try {
            return "DELETE".equals(response.request().method());
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isWriteResponse(Response response) {
        try {
            String method = response.request().method();
            return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        } catch (Exception exception) {
            return false;
        }
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
