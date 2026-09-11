package com.vigilx.pages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Organisation -&gt; Project Information: a sibling tab of the Profile tab
 * {@link OrganizationPage} already handles - updates the project logo, the Description field
 * under Project Basic Information (twice, so the final save is the one verified as displayed),
 * Project Admin Details' Last Name, and Address Details' Address Line 1. A dedicated class per
 * this project's convention of one page object per distinct screen/tab (mirrors
 * {@link GroupsPage} / {@link RolesPage} sitting alongside {@link UsersRolesPage}); does not
 * modify {@link OrganizationPage}.
 *
 * <p>The project logo upload reuses the same native-file-chooser interception
 * {@link OrganizationPage#uploadLogo(String)} uses (confirmed live: clicking the edit-logo button
 * in a real, headed browser fires the hidden file input's own click handler, which opens a genuine
 * OS "Open" dialog that nothing then closes unless the chooser is intercepted before it renders).
 * Every save is confirmed by the actual toast text, never a hard-coded toast id. Contract: never
 * throws into the caller; every failure is logged and returned as {@code false}.
 */
public class ProjectInformationPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public ProjectInformationPage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full flow: {@link #navigateToProjectInformation()} -&gt; {@link #updateProjectLogo(String)}
     * -&gt; {@link #updateProjectDescription(String)} called twice (an interim value, then
     * {@code data.finalDescription()}, so the last save is the one verified as displayed) -&gt;
     * {@link #updateProjectAdminDetails(String)} -&gt; {@link #updateProjectAddress(String)}.
     */
    public boolean runProjectInformationFlow(ProjectInformationData data) {
        boolean navigated = navigateToProjectInformation();
        boolean logoOk = navigated && updateProjectLogo(data.logoPath());
        boolean descriptionInterimOk = navigated && updateProjectDescription(data.description());
        boolean descriptionFinalOk = navigated && updateProjectDescription(data.finalDescription());
        boolean adminOk = navigated && updateProjectAdminDetails(data.adminLastName());
        boolean addressOk = navigated && updateProjectAddress(data.addressLine1());
        System.out.println("[PROJECT INFORMATION] Flow: navigated=" + navigated + " logo=" + logoOk
                + " descriptionInterim=" + descriptionInterimOk + " descriptionFinal=" + descriptionFinalOk
                + " admin=" + adminOk + " address=" + addressOk);
        return navigated && logoOk && descriptionInterimOk && descriptionFinalOk && adminOk && addressOk;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /**
     * Clicks the "Project Information" tab (assumes the Organisation page is already open, as it
     * is right after {@link OrganizationPage}'s own flow runs - independent of that class, so it
     * re-opens Organisation itself if the tab is not already visible) and verifies the tab loaded.
     */
    public boolean navigateToProjectInformation() {
        Locator tab = projectInformationTab();
        if (!SoakUiUtils.isVisibleQuietly(tab)) {
            try {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                if (SoakUiUtils.isVisibleQuietly(openSettings)) {
                    openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(500);
                }
                Locator organisationLink = page.getByText(
                        Pattern.compile("organisation|organization", Pattern.CASE_INSENSITIVE)).first();
                if (SoakUiUtils.waitVisible(organisationLink, ELEMENT_TIMEOUT_MS)) {
                    organisationLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(800);
                }
            } catch (Exception exception) {
                System.err.println("[PROJECT INFORMATION]   Could not open Organisation: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                return false;
            }
        }
        if (!SoakUiUtils.waitVisible(tab, 4000)) {
            // Confirmed live: the "Project Information" tab can sit inside a tablist container
            // (accessible name "Organization information") that needs its own click to reveal the
            // tab first, on top of the Organisation page having already opened.
            Locator tablist = page.getByRole(AriaRole.TABLIST,
                    new Page.GetByRoleOptions().setName("Organization information").setExact(false)).first();
            if (SoakUiUtils.isVisibleQuietly(tablist)) {
                try {
                    tablist.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(400);
                } catch (Exception ignored) {
                    // Best effort - the direct tab wait below is the real gate.
                }
            }
        }
        if (!SoakUiUtils.waitVisible(tab, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[PROJECT INFORMATION]   'Project Information' tab not found.");
            return false;
        }
        try {
            tab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[PROJECT INFORMATION]   'Project Information' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean loaded = verifyProjectInformationPage();
        System.out.println("[PROJECT INFORMATION] Project Information tab opened: " + (loaded ? "YES" : "NO"));
        return loaded;
    }

    /** Confirms at least one of the page's stable edit controls is present. */
    public boolean verifyProjectInformationPage() {
        boolean any = SoakUiUtils.isVisibleQuietly(editProjectLogoButton())
                || SoakUiUtils.isVisibleQuietly(editButton("Edit Project Admin Details"))
                || SoakUiUtils.isVisibleQuietly(editButton("Edit Project Basic Information"));
        System.out.println("[PROJECT INFORMATION]   Page controls present: " + any);
        return any;
    }

    // ---------------------------------------------------------------------
    // Project logo
    // ---------------------------------------------------------------------

    /**
     * Uploads a new project logo: clicks "Edit project logo", intercepting the native OS file
     * chooser the button's own click handler would otherwise pop open in a real, headed browser
     * (the same fix applied in {@link OrganizationPage#uploadLogo(String)}) so no system window is
     * ever left open, works through the crop dialog's confirm ("Save changes" - and a possible
     * second, separate "Save" step if the build shows one, mirroring the Organisation logo flow),
     * and confirms the actual success toast text.
     */
    public boolean updateProjectLogo(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            System.out.println("[PROJECT INFORMATION]   No logo path configured; skipping.");
            return false;
        }
        Path resolved = Paths.get(logoPath).toAbsolutePath();
        if (!Files.exists(resolved)) {
            System.out.println("[PROJECT INFORMATION]   Logo file not found at " + resolved + "; skipping.");
            return false;
        }
        Locator editLogo = editProjectLogoButton();
        if (!SoakUiUtils.waitVisible(editLogo, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[PROJECT INFORMATION]   'Edit project logo' control not present; skipping.");
            return false;
        }
        try {
            FileChooser chooser = null;
            try {
                chooser = page.waitForFileChooser(new Page.WaitForFileChooserOptions().setTimeout(5000),
                        () -> editLogo.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS)));
            } catch (Exception noChooser) {
                if (!SoakUiUtils.isVisibleQuietly(page.locator("input[type=\"file\"]").first())) {
                    editLogo.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                }
            }
            if (chooser != null) {
                chooser.setFiles(resolved);
            } else {
                // Fallback only: scoped to whichever file input is newly associated with the crop
                // dialog that just opened, rather than an index-based nth() pick across every file
                // input on the page.
                Locator fileInput = page.locator("[role='dialog'] input[type=\"file\"]")
                        .or(page.locator("input[type=\"file\"]").last())
                        .first();
                if (fileInput.count() == 0) {
                    System.err.println("[PROJECT INFORMATION]   Project logo file input not found.");
                    SoakUiUtils.closeOpenDialogs(page);
                    return false;
                }
                fileInput.setInputFiles(resolved);
            }
            page.waitForTimeout(800);

            Locator updatePicture = page.locator("button")
                    .filter(new Locator.FilterOptions().setHasText(Pattern.compile(
                            "update picture|save changes", Pattern.CASE_INSENSITIVE))).first();
            if (!SoakUiUtils.waitVisible(updatePicture, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[PROJECT INFORMATION]   Crop dialog's confirm button not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            updatePicture.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            Locator finalSave = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save").setExact(true)).first();
            if (SoakUiUtils.waitVisible(finalSave, 4000)) {
                finalSave.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(600);
            }

            boolean toastOk = waitForSuccessToast("Project logo update", toastBefore);
            SoakUiUtils.closeOpenDialogs(page);

            boolean windowClosed = !SoakUiUtils.isAnyDialogOpen(page);
            System.out.println("[PROJECT INFORMATION]   Project logo uploaded: toast=" + toastOk
                    + " windowClosed=" + windowClosed);
            return toastOk && windowClosed;
        } catch (Exception exception) {
            System.err.println("[PROJECT INFORMATION]   Project logo upload failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Admin Details / Basic Information / Address
    // ---------------------------------------------------------------------

    /**
     * Opens "Edit Project Admin Details", optionally changes only the Last Name field (skipped
     * when {@code newLastName} is blank, exercising a plain save-without-modification), saves, and
     * confirms both the success toast and - when a name was actually changed - that it is now
     * displayed.
     */
    public boolean updateProjectAdminDetails(String newLastName) {
        return editAndSave("Edit Project Admin Details", "Last Name", newLastName,
                "Project Admin Details update");
    }

    /**
     * Opens "Edit Project Basic Information", optionally changes only the Description field
     * (skipped when {@code newDescription} is blank), saves, and confirms both the success toast
     * and - when the description was actually changed - that it is now displayed.
     */
    public boolean updateProjectDescription(String newDescription) {
        return editAndSave("Edit Project Basic Information", "Description", newDescription,
                "Project Basic Information update");
    }

    /**
     * Opens "Edit Address Details", optionally changes only Address Line 1 (skipped when
     * {@code newAddressLine1} is blank; Address Line 2 and every other address field are left
     * untouched), saves, and confirms both the success toast and - when the value was actually
     * changed - that it is now displayed.
     */
    public boolean updateProjectAddress(String newAddressLine1) {
        return editAndSave("Edit Address Details", "Address Line 1", newAddressLine1,
                "Project Address update");
    }

    /**
     * Shared shape behind {@link #updateProjectAdminDetails(String)},
     * {@link #updateProjectDescription(String)} and {@link #updateProjectAddress(String)}: click
     * {@code editButtonName}, optionally change {@code fieldLabel} (skipped when {@code newValue}
     * is blank - a plain save-without-modification), click "Save changes", confirm the toast, and
     * - when a value was actually changed - confirm it is now displayed on the page.
     */
    private boolean editAndSave(String editButtonName, String fieldLabel, String newValue, String toastContext) {
        Locator editButton = editButton(editButtonName);
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[PROJECT INFORMATION]   '" + editButtonName + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean changingValue = newValue != null && !newValue.isBlank();
            boolean fieldOk = !changingValue || fillLabeledField(fieldLabel, newValue);

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes")).first();
            if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[PROJECT INFORMATION]   '" + editButtonName + "' 'Save changes' control"
                        + " not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            saveChanges.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            boolean toastOk = waitForSuccessToast(toastContext, toastBefore);
            SoakUiUtils.closeOpenDialogs(page);

            boolean valueShown = !changingValue
                    || SoakUiUtils.isVisibleQuietly(page.getByText(newValue,
                            new Page.GetByTextOptions().setExact(false)).first());
            System.out.println("[PROJECT INFORMATION]   " + toastContext + ": changed=" + changingValue
                    + " fieldSet=" + fieldOk + " toast=" + toastOk + " valueShown=" + valueShown);
            return fieldOk && toastOk && valueShown;
        } catch (Exception exception) {
            System.err.println("[PROJECT INFORMATION]   " + toastContext + " failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /** Fills a textbox identified by its associated label text (accessible name), exact match. */
    private boolean fillLabeledField(String label, String value) {
        Locator field = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(label).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[PROJECT INFORMATION]   '" + label + "' field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(value);
            return true;
        } catch (Exception exception) {
            System.err.println("[PROJECT INFORMATION]   '" + label + "' could not be filled: "
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
     * {@link RolesPage}/{@link GroupsPage}/{@link OrganizationPage}.
     */
    private boolean waitForSuccessToast(String context, String before) {
        long deadline = System.currentTimeMillis() + 6000;
        Pattern bad = Pattern.compile("fail|error|unable|could not|invalid|required|not (saved|updated|created)",
                Pattern.CASE_INSENSITIVE);
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                boolean ok = !bad.matcher(toast).find();
                System.out.println("[PROJECT INFORMATION]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[PROJECT INFORMATION]   " + context + ": no notification observed within 6s.");
        return false;
    }

    // ---------------------------------------------------------------------
    // Locators
    // ---------------------------------------------------------------------

    private Locator projectInformationTab() {
        return page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Project Information").setExact(false)).first();
    }

    private Locator editProjectLogoButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit project logo").setExact(false)).first();
    }

    private Locator editButton(String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name)).first();
    }
}
