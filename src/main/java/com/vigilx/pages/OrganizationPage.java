package com.vigilx.pages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.LoggerUtils;
import com.vigilx.utils.SoakUiUtils;


/**
 * Settings -&gt; Organisation -&gt; Profile. Pre-existing navigation/verification methods
 * ({@link #isOrganizationPageLoaded()}, {@link #clickEdit()}, {@link #clickSave()}, etc.) are
 * unchanged. {@link #uploadLogo(String)} / {@link #updateAdminDetails(String)} /
 * {@link #updatePrimaryContact(String)} / {@link #updateAddressDetails(String, String)} /
 * {@link #navigateToOrganization()} / {@link #runOrganizationUpdateFlow(OrganizationData)} are
 * additive.
 */
public class OrganizationPage extends BasePage {

    private static final Logger logger =
            LoggerUtils.getLogger(OrganizationPage.class);

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    //=========================================================
    // Locators
    //=========================================================

    private final Locator btnEditLogo;
    private final Locator btnEdit;
    private final Locator btnSave;
    private final Locator btnProjectInformation;
    private final Locator lblOrganizationName;
    private final Locator lblOrganizationHeader;
    //=========================================================
    // Constructor
    //=========================================================

    public OrganizationPage(Page page) {

        super(page);
        lblOrganizationName = page.locator("div.org-title");

        // Confirmed live: the real control is the small icon-only pencil button overlaid on the
        // logo avatar, aria-label "Edit organization logo" / class "logo-edit-btn" - "button.edit-logo"
        // never matched anything.
        btnEditLogo = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName("Edit organization logo"))
                .or(page.locator("button.logo-edit-btn"));

        btnEdit = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Edit")
                        .setExact(true));

        btnSave = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Save"));

        btnProjectInformation = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Project Information"));

        // Confirmed live: "h4.page-title" alone no longer matches anything on this build's
        // Organisation page (the heading text "Organization" is still there, just not on an h4 -
        // this made isOrganizationPageLoaded() hang for its full default timeout). Kept as the
        // first try for whatever page still renders it this way, with a role-based fallback that
        // matches the visible "Organization" heading regardless of its tag.
        lblOrganizationHeader = page.locator("h4.page-title")
                .or(page.getByRole(AriaRole.HEADING,
                        new Page.GetByRoleOptions().setName("Organization").setExact(false)))
                .first();

    }

    //=========================================================
    // Validation
    //=========================================================

 public boolean isOrganizationPageLoaded() {

    logger.info("Verifying Organization Page");

    return SoakUiUtils.waitVisible(lblOrganizationHeader, ELEMENT_TIMEOUT_MS);
}
   public boolean isOrganizationNameDisplayed() {

    return lblOrganizationName.isVisible();

}
    //=========================================================
    // Actions
    //=========================================================

    public OrganizationPage clickEditLogo() {

        logger.info("Clicking Edit Logo");

        actions.click(btnEditLogo);

        return this;

    }

    /**
     * Uploads a new organization logo: clicks the "Edit organization logo" icon next to the logo
     * avatar, sets {@code logoPath} on the resulting (hidden) file input, then works through the
     * app's own two-step confirm - confirmed live via a real recording: the crop dialog's visible
     * "Update Picture" button carries the accessible name "Save changes" (click it first), which
     * then reveals a second, separate "Save"/"Cancel" pair that actually persists the change
     * (click the exact "Save"). Every save here is confirmed by the actual "Logo updated
     * successfully" toast text (never a hard-coded toast id) before the window is guaranteed
     * closed. Also intercepts the native OS "Open" file-picker window a real, headed browser would
     * otherwise pop open when the edit-logo button's own click handler fires the hidden file
     * input's click (confirmed live via screenshot) - {@code waitForFileChooser} prevents that
     * window from ever rendering at all, falling back to locating the input directly if no
     * chooser event fires. A blank/missing path skips cleanly (logged, returns {@code false}) so
     * the rest of the Organisation checks still run. Never throws into the caller.
     */
    public boolean uploadLogo(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            System.out.println("[ORGANISATION]   No logo path configured; skipping.");
            return false;
        }
        Path resolved = Paths.get(logoPath).toAbsolutePath();
        if (!Files.exists(resolved)) {
            System.out.println("[ORGANISATION]   Logo file not found at " + resolved + "; skipping.");
            return false;
        }
        if (!SoakUiUtils.waitVisible(btnEditLogo, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[ORGANISATION]   'Edit organization logo' control not present; skipping.");
            return false;
        }
        try {
            FileChooser chooser = null;
            try {
                chooser = page.waitForFileChooser(new Page.WaitForFileChooserOptions().setTimeout(5000),
                        () -> btnEditLogo.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS)));
            } catch (Exception noChooser) {
                if (!SoakUiUtils.isVisibleQuietly(page.locator("input[type=\"file\"]").first())) {
                    btnEditLogo.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                }
            }
            if (chooser != null) {
                chooser.setFiles(resolved);
            } else {
                Locator fileInput = page.locator("input[type=\"file\"]").first();
                if (fileInput.count() == 0) {
                    System.err.println("[ORGANISATION]   Logo file input not found.");
                    SoakUiUtils.closeOpenDialogs(page);
                    return false;
                }
                fileInput.setInputFiles(resolved);
            }
            page.waitForTimeout(800);

            // Step 1: the crop dialog's own confirm - visible text "Update Picture", accessible
            // name "Save changes" (matched by text here since that is what is actually visible).
            Locator updatePicture = page.locator("button")
                    .filter(new Locator.FilterOptions().setHasText(Pattern.compile("update picture",
                            Pattern.CASE_INSENSITIVE))).first();
            if (!SoakUiUtils.waitVisible(updatePicture, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[ORGANISATION]   'Change logo' crop dialog's 'Update Picture' button not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            updatePicture.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            // Step 2: the real, final confirm - a plain, exact "Save" button on a second dialog.
            Locator finalSave = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save").setExact(true)).first();
            if (SoakUiUtils.waitVisible(finalSave, ELEMENT_TIMEOUT_MS)) {
                finalSave.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(600);
            } else {
                System.out.println("[ORGANISATION]   No second 'Save' step appeared; treating the first"
                        + " confirm as final.");
            }

            boolean toastOk = waitForSuccessToast("Logo update", toastBefore);

            // Guarantee the window is actually closed - never assume the app's own close
            // animation/handler ran; this is a top-level dialog on the Organisation page (not
            // nested inside another form dialog), so a page-wide Escape here is safe.
            SoakUiUtils.closeOpenDialogs(page);

            boolean cropperGone = page.locator("button")
                    .filter(new Locator.FilterOptions().setHasText(Pattern.compile("update picture",
                            Pattern.CASE_INSENSITIVE))).count() == 0
                    && !SoakUiUtils.isAnyDialogOpen(page);
            System.out.println("[ORGANISATION]   Logo uploaded: toast=" + toastOk + " windowClosed="
                    + cropperGone);
            return toastOk && cropperGone;
        } catch (Exception exception) {
            System.err.println("[ORGANISATION]   Logo upload failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Admin Details / Primary Contact / Address Details
    // ---------------------------------------------------------------------

    /**
     * Opens "Edit Admin Details", changes only the Last Name field, saves, and confirms both the
     * success toast and the newly displayed Last Name. Other Admin Details fields are left
     * untouched.
     */
    public boolean updateAdminDetails(String newLastName) {
        return editSectionLastName("Edit Admin Details", newLastName, "Admin Details update");
    }

    /**
     * Opens "Edit Primary Contact" and saves - if {@code newLastName} is blank, this exercises a
     * plain "save without modification"; otherwise it also changes the Last Name field first and
     * confirms the update. Other Primary Contact fields are left untouched.
     */
    public boolean updatePrimaryContact(String newLastName) {
        return editSectionLastName("Edit Primary Contact", newLastName, "Primary Contact update");
    }

    /**
     * Opens "Edit Address Details", changes only Address Line 1 and Address Line 2, saves, and
     * confirms both the success toast and the newly displayed values. Other Address Details fields
     * (Country/State/City/Zip/Time zone) are left untouched.
     */
    public boolean updateAddressDetails(String addressLine1, String addressLine2) {
        Locator editButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit Address Details")).first();
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ORGANISATION]   'Edit Address Details' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean line1Ok = fillLabeledTextbox("Address Line 1", addressLine1);
            boolean line2Ok = fillLabeledTextbox("Address Line 2", addressLine2);

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes")).first();
            if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[ORGANISATION]   Address Details 'Save changes' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            saveChanges.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            boolean toastOk = waitForSuccessToast("Address Details update", toastBefore);
            SoakUiUtils.closeOpenDialogs(page);

            boolean line1Shown = addressLine1 == null || addressLine1.isBlank()
                    || SoakUiUtils.isVisibleQuietly(page.getByText(addressLine1, new Page.GetByTextOptions()
                            .setExact(false)).first());
            boolean line2Shown = addressLine2 == null || addressLine2.isBlank()
                    || SoakUiUtils.isVisibleQuietly(page.getByText(addressLine2, new Page.GetByTextOptions()
                            .setExact(false)).first());
            System.out.println("[ORGANISATION]   Address Details updated: fieldsSet=" + (line1Ok && line2Ok)
                    + " toast=" + toastOk + " line1Shown=" + line1Shown + " line2Shown=" + line2Shown);
            return line1Ok && line2Ok && toastOk && line1Shown && line2Shown;
        } catch (Exception exception) {
            System.err.println("[ORGANISATION]   Address Details update failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Shared shape behind {@link #updateAdminDetails(String)} and
     * {@link #updatePrimaryContact(String)}: click {@code editButtonName}, optionally change the
     * "Last Name" textbox (skipped when {@code newLastName} is blank, exercising a plain
     * save-without-modification), click "Save changes", confirm the toast, and - when a name was
     * actually changed - confirm it is now displayed on the page.
     */
    private boolean editSectionLastName(String editButtonName, String newLastName, String toastContext) {
        Locator editButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(editButtonName)).first();
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ORGANISATION]   '" + editButtonName + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean changingName = newLastName != null && !newLastName.isBlank();
            boolean fieldOk = !changingName || fillLabeledTextbox("Last Name", newLastName);

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes")).first();
            if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[ORGANISATION]   '" + editButtonName + "' 'Save changes' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            saveChanges.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            boolean toastOk = waitForSuccessToast(toastContext, toastBefore);
            SoakUiUtils.closeOpenDialogs(page);

            boolean nameShown = !changingName
                    || SoakUiUtils.isVisibleQuietly(page.getByText(newLastName,
                            new Page.GetByTextOptions().setExact(false)).first());
            System.out.println("[ORGANISATION]   " + toastContext + ": changed=" + changingName
                    + " fieldSet=" + fieldOk + " toast=" + toastOk + " nameShown=" + nameShown);
            return fieldOk && toastOk && nameShown;
        } catch (Exception exception) {
            System.err.println("[ORGANISATION]   " + toastContext + " failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /** Fills a textbox identified by its associated label text (accessible name), exact match. */
    private boolean fillLabeledTextbox(String label, String value) {
        if (value == null) {
            return true;
        }
        Locator field = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(label).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ORGANISATION]   '" + label + "' field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(value);
            return true;
        } catch (Exception exception) {
            System.err.println("[ORGANISATION]   '" + label + "' could not be filled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * A visible toast/snackbar/alert, matched the same broad way {@link SoakUiUtils#readToastText}
     * is - kept as a Locator here (rather than just text) so it can actually be clicked to dismiss.
     */
    private Locator toastLocator() {
        return page.locator("[id^='common-toast'], [class*='toast' i], [role='alert'], [class*='snackbar' i]")
                .first();
    }

    /**
     * Clicks the current toast to dismiss it (confirmed live via a real recording: this app's own
     * toasts are click-to-dismiss) and waits up to 7s for it to actually disappear - confirmed
     * live: the click alone often doesn't register as a dismiss, so the real guarantee is giving
     * the toast's own auto-dismiss timer (observed comfortably under 7s) enough room. Without this,
     * two consecutive saves that happen to show the exact same generic message (confirmed live:
     * this build's Admin/Contact/Address/Project saves all toast the identical "Changes saved
     * successfully" text) leave a stale, always-non-blank toast in place - the text never changes
     * and its element never becomes invisible on its own within any reasonable wait - so
     * {@link #waitForSuccessToast}'s before/after comparison would otherwise see no difference and
     * wrongly report "no notification observed" on the second save. A missing/already-gone toast
     * is a no-op.
     */
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

    /**
     * Polls up to 6s for a new toast (never a hard-coded toast id) and checks it against a
     * failure-keyword pattern - the same convention already used per-class in
     * {@link RolesPage}/{@link GroupsPage}.
     */
    private boolean waitForSuccessToast(String context, String before) {
        long deadline = System.currentTimeMillis() + 10000;
        Pattern bad = Pattern.compile("fail|error|unable|could not|invalid|required|not (saved|updated|created)",
                Pattern.CASE_INSENSITIVE);
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                boolean ok = !bad.matcher(toast).find();
                System.out.println("[ORGANISATION]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[ORGANISATION]   " + context + ": no notification observed within 10s.");
        return false;
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Opens Settings (only if not already open) then "Organisation", landing on the same
     * Profile view {@link #uploadLogo(String)} / {@link #updateAdminDetails(String)} /
     * {@link #updatePrimaryContact(String)} / {@link #updateAddressDetails(String, String)}
     * operate on.
     */
    public boolean navigateToOrganization() {
        if (SoakUiUtils.isVisibleQuietly(lblOrganizationHeader)) {
            return true;
        }
        Locator organisationLink = page.getByText(
                Pattern.compile("organisation|organization", Pattern.CASE_INSENSITIVE)).first();
        try {
            // Don't blindly click "Open settings" - if the settings sidebar (with its Organisation
            // link, or a sibling like License/Users & Roles/Application Settings) is already open,
            // clicking it again TOGGLES IT CLOSED, hiding the very link this method needs next.
            // Confirmed live: this exact gap caused navigateToOrganization() to click "Open
            // settings" into a closed sidebar and then time out waiting for the page header.
            boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(organisationLink)
                    || SoakUiUtils.isVisibleQuietly(page.getByText(Pattern.compile(
                            "my license|users & roles|application settings", Pattern.CASE_INSENSITIVE))
                            .first());
            if (!settingsAlreadyOpen) {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                if (SoakUiUtils.isVisibleQuietly(openSettings)) {
                    openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(500);
                }
            }
            if (SoakUiUtils.waitVisible(organisationLink, ELEMENT_TIMEOUT_MS)) {
                organisationLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(800);
            }
        } catch (Exception exception) {
            System.err.println("[ORGANISATION]   Could not navigate to Organisation: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean loaded = isOrganizationPageLoaded();
        System.out.println("[ORGANISATION] Organisation page opened: " + (loaded ? "YES" : "NO"));
        return loaded;
    }

    /**
     * Full flow: {@link #navigateToOrganization()} -&gt; {@link #uploadLogo(String)} -&gt;
     * {@link #updateAdminDetails(String)} -&gt; {@link #updatePrimaryContact(String)} called once
     * with no name change (a plain save) and once with {@code data.primaryContactLastName()} -&gt;
     * {@link #updateAddressDetails(String, String)}.
     */
    public boolean runOrganizationUpdateFlow(OrganizationData data) {
        boolean navigated = navigateToOrganization();
        boolean logoOk = navigated && uploadLogo(data.logoPath());
        boolean adminOk = navigated && updateAdminDetails(data.adminLastName());
        boolean contactSavedUnchangedOk = navigated && updatePrimaryContact(null);
        boolean contactUpdatedOk = navigated && updatePrimaryContact(data.primaryContactLastName());
        boolean addressOk = navigated && updateAddressDetails(data.addressLine1(), data.addressLine2());
        System.out.println("[ORGANISATION] Update flow: navigated=" + navigated + " logo=" + logoOk
                + " admin=" + adminOk + " contactSavedUnchanged=" + contactSavedUnchangedOk
                + " contactUpdated=" + contactUpdatedOk + " address=" + addressOk);
        return navigated && logoOk && adminOk && contactSavedUnchangedOk && contactUpdatedOk && addressOk;
    }

    public OrganizationPage clickEdit() {

        logger.info("Clicking Edit");

        actions.click(btnEdit);

        return this;

    }

    public OrganizationPage clickSave() {

        logger.info("Clicking Save");

        actions.click(btnSave);

        return this;

    }

    public OrganizationPage clickProjectInformation() {

        logger.info("Opening Project Information");

        actions.click(btnProjectInformation);

        return this;

    }

    //=========================================================
    // Getters
    //=========================================================

    public String getPageTitle() {

        return actions.getTitle();

    }

    public String getCurrentUrl() {

        return actions.getCurrentUrl();

    }
   public String getOrganizationName() {

    return lblOrganizationName.innerText().trim();

}

}
