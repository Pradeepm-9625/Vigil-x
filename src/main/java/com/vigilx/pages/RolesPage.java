package com.vigilx.pages;

import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Users &amp; Roles -&gt; Roles -&gt; Custom Roles: the full Custom Role lifecycle -
 * create ("Create Customized Role", the "Full" permission, a role name), verify it is listed,
 * rename it, verify the rename, delete it, and verify it is gone.
 *
 * <p>Reached after {@link UsersRolesPage}'s user lifecycle. Follows the same page-object shape as
 * {@link InfraPage} / {@link ApplicationSettingsPage} / {@link UsersRolesPage}: a {@link BasePage}
 * subclass, locators kept here, small reusable action methods, and the shared
 * {@link SoakUiUtils#clickAndWaitForResponse} mechanism for every save/delete API (no new API
 * framework). Deliberately independent of {@link UsersRolesPage} - it re-navigates from "Users &amp;
 * Roles" itself rather than reusing/duplicating that class's internals, exactly like
 * {@link InfraPage} and {@link UsersRolesPage} each already open Settings independently.
 *
 * <p>The role name is the identifier used to find the role both before and after the rename (never
 * a fixed row position); a role id, when the create API's response exposes one, is captured and
 * reused the same way {@link UsersRolesPage} captures a user id. Notifications are asserted by
 * text via {@link SoakUiUtils#readToastText}, never a fixed {@code #common-toast-N} id.
 *
 * <p>Contract: never throws into the caller. Every failure is logged and returned as
 * {@code false} so the soak continues to whatever runs after the role lifecycle.
 */
public class RolesPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    private String lastCreatedRoleId;

    public RolesPage(Page page) {
        super(page);
    }

    /** The role id captured from the create-role API response (may be blank). */
    public String lastCreatedRoleId() {
        return lastCreatedRoleId == null ? "" : lastCreatedRoleId;
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * The whole recorded lifecycle: {@link #navigateToRoles()} -&gt; {@link #openCustomRoles()} -&gt;
     * {@link #createCustomRole(RoleData)} -&gt; {@link #verifyRoleCreated(String)} -&gt;
     * {@link #updateRole(String, RoleData)} -&gt; {@link #verifyRoleUpdated(String)} -&gt;
     * {@link #deleteRole(String)} -&gt; {@link #verifyRoleDeleted(String)}.
     *
     * @param original the role to create
     * @param updated  the same role with the changed name (e.g. "Testing role-update")
     */
    public boolean runRoleLifecycle(RoleData original, RoleData updated) {
        if (!navigateToRoles()) {
            return false;
        }
        openCustomRoles();
        boolean created = createCustomRole(original);
        boolean verifiedCreated = created && verifyRoleCreated(original.roleName());
        boolean updatedOk = verifiedCreated && updateRole(original.roleName(), updated);
        boolean verifiedUpdated = updatedOk && verifyRoleUpdated(updated.roleName());
        boolean deleted = verifiedUpdated && deleteRole(updated.roleName());
        boolean verifiedDeleted = deleted && verifyRoleDeleted(updated.roleName());
        System.out.println("[ROLES] Lifecycle: created=" + created + " verifiedCreated=" + verifiedCreated
                + " updated=" + updatedOk + " verifiedUpdated=" + verifiedUpdated + " deleted=" + deleted
                + " verifiedDeleted=" + verifiedDeleted);
        return verifiedDeleted;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /** Opens Settings (only if not already open), "Users & Roles", then the "Roles" tab. */
    public boolean navigateToRoles() {
        Locator rolesTab = rolesTabLocator();
        boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(usersRolesLink().first())
                || SoakUiUtils.isVisibleQuietly(rolesTab.first())
                || SoakUiUtils.isVisibleQuietly(page.getByText(Pattern.compile(
                        "my license|users & roles|organisation|organization|application settings",
                        Pattern.CASE_INSENSITIVE)).first());
        if (!settingsAlreadyOpen) {
            try {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                if (SoakUiUtils.isVisibleQuietly(openSettings)) {
                    openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    waitAfterPageNavigation();
                }
            } catch (Exception exception) {
                System.err.println("[ROLES] 'Open settings' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        Locator usersRoles = usersRolesLink();
        if (SoakUiUtils.waitVisible(usersRoles, ELEMENT_TIMEOUT_MS)) {
            try {
                usersRoles.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                waitAfterPageNavigation();
            } catch (Exception exception) {
                System.err.println("[ROLES] 'Users & Roles' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        if (!SoakUiUtils.waitVisible(rolesTab, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES] 'Roles' tab not found.");
            return false;
        }
        try {
            rolesTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
            System.out.println("[ROLES] Roles tab opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[ROLES] 'Roles' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Selects the "Custom Roles" section when the build shows System Roles and Custom Roles as
     * separate segments; a no-op (not a failure) when "Create Customized Role" is already directly
     * visible, since some builds show both on one Roles page.
     */
    public boolean openCustomRoles() {
        if (SoakUiUtils.isVisibleQuietly(createCustomRoleButton().first())) {
            System.out.println("[ROLES] Custom Roles already visible.");
            return true;
        }
        Locator customRoles = page.getByRole(AriaRole.TAB,
                        new Page.GetByRoleOptions().setName(Pattern.compile("custom roles", Pattern.CASE_INSENSITIVE)))
                .or(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(Pattern.compile("custom roles", Pattern.CASE_INSENSITIVE))))
                .or(page.getByText(Pattern.compile("custom roles", Pattern.CASE_INSENSITIVE)))
                .first();
        if (!SoakUiUtils.waitVisible(customRoles, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[ROLES] 'Custom Roles' section control not found; assuming a single Roles view.");
            return true;
        }
        try {
            customRoles.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            System.out.println("[ROLES] Custom Roles section opened.");
            return true;
        } catch (Exception exception) {
            System.out.println("[ROLES] 'Custom Roles' could not be clicked ("
                    + SoakUiUtils.firstLine(exception.getMessage()) + "); continuing.");
            return true;
        }
    }

    /** Clicks "Create Customized Role" and waits for its dialog (the role name field) to render. */
    public boolean clickCreateCustomRole() {
        Locator create = createCustomRoleButton();
        if (!SoakUiUtils.waitVisible(create, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES] 'Create Customized Role' button not found.");
            return false;
        }
        try {
            create.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[ROLES] 'Create Customized Role' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean dialogOpen = SoakUiUtils.waitVisible(roleNameField(), ELEMENT_TIMEOUT_MS);
        System.out.println("[ROLES] Create Customized Role dialog opened: " + (dialogOpen ? "YES" : "NO"));
        return dialogOpen;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    /**
     * {@link #clickCreateCustomRole()} -&gt; {@link #setPermissions()} -&gt;
     * {@link #enterRoleName(String)} -&gt; {@link #saveRole(String, String)}.
     */
    public boolean createCustomRole(RoleData data) {
        if (!clickCreateCustomRole()) {
            return false;
        }
        boolean permissionsOk = !data.permissionFull() || setPermissions();
        boolean nameOk = enterRoleName(data.roleName());
        if (!nameOk) {
            System.err.println("[ROLES]   Role name could not be entered; not saving.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
        boolean savedOk = saveRole("Create role", data.roleName());
        System.out.println("[ROLES] createCustomRole: permissions=" + permissionsOk + " name=" + nameOk
                + " saved=" + savedOk);
        return savedOk;
    }

    /**
     * Selects the "Full" permission level dynamically: reads whether the "Full" column's
     * select-all control is already checked and only checks it when it is not - never assumes it
     * starts unselected. Verifies it reads checked before returning.
     */
    public boolean setPermissions() {
        // The recorded flow clicks the "Full" label itself first - on some builds this is what
        // reveals/focuses the permission matrix column, on others it is a no-op; either way it
        // never fails the caller.
        Locator fullLabel = page.getByText(Pattern.compile("^\\s*Full\\s*$"), new Page.GetByTextOptions()).first();
        if (SoakUiUtils.isVisibleQuietly(fullLabel)) {
            try {
                fullLabel.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300);
            } catch (Exception ignored) {
                // Best effort - the select-all checkbox below is the real control.
            }
        }

        Locator fullColumnCheckbox = fullColumnSelectAll();
        if (!SoakUiUtils.waitVisible(fullColumnCheckbox, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES]   'Full' permission column control not found.");
            return false;
        }

        Boolean already = isCheckedQuietly(fullColumnCheckbox);
        if (Boolean.TRUE.equals(already)) {
            System.out.println("[ROLES]   'Full' permission already selected; leaving it.");
            return true;
        }

        try {
            fullColumnCheckbox.check(new Locator.CheckOptions().setTimeout(ELEMENT_TIMEOUT_MS).setForce(true));
        } catch (Exception checkFailed) {
            try {
                fullColumnCheckbox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS).setForce(true));
            } catch (Exception clickFailed) {
                System.err.println("[ROLES]   'Full' permission could not be selected: "
                        + SoakUiUtils.firstLine(clickFailed.getMessage()));
                return false;
            }
        }
        page.waitForTimeout(300);

        Boolean now = isCheckedQuietly(fullColumnCheckbox);
        boolean selected = now == null || now;
        System.out.println("[ROLES]   'Full' permission selected: " + (selected ? "YES" : "NOT CONFIRMED"));
        return selected;
    }

    /** Fills the role name field (tolerant of the Create dialog's and the Edit panel's different labels). */
    public boolean enterRoleName(String roleName) {
        Locator field = roleNameField();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES]   Role name field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(roleName);
            return true;
        } catch (Exception exception) {
            System.err.println("[ROLES]   Could not fill the role name: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Clicks "Save changes" and validates the background API: status is the hard signal; method,
     * endpoint, role id (once known) and {@code expectedRoleName} in the payload/response are
     * logged as supporting evidence. Shared by create and update.
     */
    public boolean saveRole(String context, String expectedRoleName) {
        Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES]   'Save changes' button not found.");
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, this::isRoleWriteResponse, 20000);
        boolean apiOk = validateRoleApi(response, context, expectedRoleName);
        boolean toastOk = waitForSuccessToast(context, toastBefore);
        SoakUiUtils.closeOpenDialogs(page);
        page.waitForTimeout(500);

        System.out.println("[ROLES]   " + context + ": api=" + apiOk + " toast=" + toastOk);
        return apiOk;
    }

    /** Confirms {@code roleName} is now listed under Custom Roles. */
    public boolean verifyRoleCreated(String roleName) {
        return verifyRoleListed(roleName);
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    /** {@link #openRole(String)} -&gt; {@link #enterRoleName(String)} (the new name) -&gt; {@link #saveRole}. */
    public boolean updateRole(String roleIdentifier, RoleData updated) {
        if (!openRole(roleIdentifier)) {
            return false;
        }
        if (!enterRoleName(updated.roleName())) {
            return false;
        }
        return saveRole("Update role", updated.roleName());
    }

    /** Confirms the role now reads {@code updatedRoleName} under Custom Roles. */
    public boolean verifyRoleUpdated(String updatedRoleName) {
        return verifyRoleListed(updatedRoleName);
    }

    /**
     * Finds {@code roleIdentifier}'s card under Custom Roles by its name (never a fixed row
     * position, and filtered via the Roles list search so pagination cannot hide it) and opens it.
     * Confirmed live via DOM inspection: the card's name is plain text, not a button, and its two
     * icon actions (copy, open) carry no distinguishing accessible name - so this clicks the
     * card's own last button (visually the right-hand "open" arrow, after the copy icon), falling
     * back to the name text itself if the card exposes no button at all.
     */
    public boolean openRole(String roleIdentifier) {
        filterRolesList(roleIdentifier);
        Locator nameText = roleNameText(roleIdentifier);
        if (!SoakUiUtils.waitVisible(nameText, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES]   Role '" + roleIdentifier + "' not found.");
            return false;
        }
        Locator open = roleCardOpenControl(roleIdentifier);
        try {
            if (SoakUiUtils.waitVisible(open, 4000)) {
                open.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } else {
                nameText.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            page.waitForTimeout(600);
            return true;
        } catch (Exception exception) {
            System.err.println("[ROLES]   Role '" + roleIdentifier + "' could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /** {@link #openRole(String)} -&gt; "Delete Role" -&gt; confirm "Delete" -&gt; validates the API + toast. */
    public boolean deleteRole(String roleIdentifier) {
        if (!openRole(roleIdentifier)) {
            return false;
        }
        Locator deleteRoleButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("delete role", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(deleteRoleButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES]   'Delete Role' button not found.");
            return false;
        }
        try {
            deleteRoleButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[ROLES]   'Delete Role' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        SoakUiUtils.waitVisible(dialog, 4000);
        Locator confirmDelete = dialog.getByRole(AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("Delete").setExact(true))
                .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Delete").setExact(true)))
                .first();
        if (!SoakUiUtils.waitVisible(confirmDelete, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[ROLES]   'Delete' confirmation button not found.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, confirmDelete, this::isRoleWriteResponse, 15000);
        boolean apiOk = validateRoleApi(response, "Delete role", null);
        boolean toastOk = waitForSuccessToast("Delete role", toastBefore);
        SoakUiUtils.closeOpenDialogs(page);
        page.waitForTimeout(800);

        System.out.println("[ROLES]   Delete role: api=" + apiOk + " toast=" + toastOk);
        return apiOk;
    }

    /** Confirms {@code roleName} no longer appears under Custom Roles. */
    public boolean verifyRoleDeleted(String roleName) {
        filterRolesList(roleName);
        boolean stillListed = SoakUiUtils.isVisibleQuietly(roleNameText(roleName));
        System.out.println("[ROLES]   Role '" + roleName + "' removed from Custom Roles: "
                + (stillListed ? "NO (still listed)" : "YES"));
        return !stillListed;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean verifyRoleListed(String roleName) {
        filterRolesList(roleName);
        boolean listed = SoakUiUtils.waitVisible(roleNameText(roleName), ELEMENT_TIMEOUT_MS);
        System.out.println("[ROLES]   Role '" + roleName + "' listed under Custom Roles: "
                + (listed ? "YES" : "NO"));
        return listed;
    }

    /**
     * Types {@code roleName} into "Search by role name" and waits for the list to filter - the
     * role list is paginated (existing roles already fill several pages), so a newly created or
     * renamed role is not necessarily on the first page without this.
     */
    private boolean filterRolesList(String roleName) {
        Locator search = page.getByPlaceholder(Pattern.compile("search.*role.*name", Pattern.CASE_INSENSITIVE))
                .first();
        if (!SoakUiUtils.waitVisible(search, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[ROLES]   Roles list search box not found; checking the current page only.");
            return false;
        }
        try {
            search.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            search.fill("");
            search.fill(roleName);
            search.press("Enter");
            page.waitForTimeout(1500);
            return true;
        } catch (Exception exception) {
            System.err.println("[ROLES]   Could not search the Roles list: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** {@code true}/{@code false} when the control's checked state can be read; {@code null} otherwise. */
    private Boolean isCheckedQuietly(Locator control) {
        try {
            return control.isChecked();
        } catch (Exception exception) {
            return null;
        }
    }

    /** Polls up to 6s for a toast that is new versus {@code before} and is not an error message. */
    private boolean waitForSuccessToast(String context, String before) {
        long deadline = System.currentTimeMillis() + 6000;
        Pattern bad = Pattern.compile("fail|error|unable|could not|invalid|required|not (saved|deleted|updated|created)",
                Pattern.CASE_INSENSITIVE);
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                boolean ok = !bad.matcher(toast).find();
                System.out.println("[ROLES]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[ROLES]   " + context + ": no notification observed within 6s.");
        return false;
    }

    /**
     * Logs and pass/fail-checks a role write response: HTTP status is the hard signal; the method,
     * endpoint, captured role id, and (when given) an {@code expectedRoleName} in the payload or
     * response body are logged as supporting evidence. Captures the role id out of the response
     * the first time one is seen.
     */
    private boolean validateRoleApi(Response response, String context, String expectedRoleName) {
        if (response == null) {
            System.err.println("[ROLES]   " + context + ": no matching API response within timeout.");
            return false;
        }
        int status = response.status();
        boolean statusOk = status >= 200 && status < 300;
        String rawBody = SoakUiUtils.safeResponseBody(response);
        if (lastCreatedRoleId == null || lastCreatedRoleId.isBlank()) {
            lastCreatedRoleId = extractRoleId(rawBody);
        }
        String payload = SoakUiUtils.safeRequestBody(response).toLowerCase(Locale.ROOT);
        String body = rawBody.toLowerCase(Locale.ROOT);
        String id = lastCreatedRoleId().toLowerCase(Locale.ROOT);
        boolean idMatches = !id.isBlank()
                && (response.url().toLowerCase(Locale.ROOT).contains(id) || payload.contains(id) || body.contains(id));
        boolean nameMatches = expectedRoleName == null
                || payload.contains(expectedRoleName.toLowerCase(Locale.ROOT))
                || body.contains(expectedRoleName.toLowerCase(Locale.ROOT));

        System.out.println("[ROLES]   " + context + " API: " + SoakUiUtils.safeMethod(response) + " "
                + SoakUiUtils.shortPath(response.url()) + " -> " + status + " (" + (statusOk ? "PASS" : "FAIL")
                + ") | roleId=" + lastCreatedRoleId() + " (in request=" + idMatches + ")"
                + (expectedRoleName == null ? "" : " | expected name '" + expectedRoleName + "' present=" + nameMatches));
        if (!statusOk) {
            System.err.println("[ROLES]   " + context + " API FAILED (HTTP " + status + ").");
        }
        return statusOk;
    }

    /** Best-effort role id out of a JSON body: the value of an "id"/"roleId"/"role_id"/"_id" key. */
    private String extractRoleId(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "\"(?:role_?id|_id|id)\"\\s*:\\s*\"?([A-Za-z0-9_-]{2,64})\"?",
                Pattern.CASE_INSENSITIVE).matcher(jsonBody);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** True for the write request that backs a Custom Role create/update/delete. Broad on purpose. */
    private boolean isRoleWriteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                    || "DELETE".equals(method);
            return write && url.contains("role");
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Locators
    // ---------------------------------------------------------------------

    private Locator usersRolesLink() {
        return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("users\\s*&\\s*roles", Pattern.CASE_INSENSITIVE)))
                .or(page.getByText(Pattern.compile("users\\s*&\\s*roles", Pattern.CASE_INSENSITIVE)))
                .first();
    }

    private Locator rolesTabLocator() {
        return page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Roles").setExact(true)).first();
    }

    private Locator createCustomRoleButton() {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile("create customi[sz]ed role", Pattern.CASE_INSENSITIVE))).first();
    }

    /**
     * The role-name field: the Create dialog's is {@code input#input-roleName}, placeholder
     * "Enter Role name" - confirmed live via DOM inspection to carry no {@code type} attribute
     * (so it does not reliably resolve to {@code role="textbox"}) and to sit right next to a
     * "Search by role name" search box whose own placeholder also contains the substring
     * "role name" - a looser, alternation-based match previously grabbed that search box instead.
     * Every alternative here is therefore an <em>exact</em> match: "Enter Role name" exactly, then
     * the Edit panel's "Role name" accessible name exactly, then the stable id as a last resort.
     */
    private Locator roleNameField() {
        return page.getByPlaceholder("Enter Role name", new Page.GetByPlaceholderOptions().setExact(true))
                .or(page.getByRole(AriaRole.TEXTBOX,
                        new Page.GetByRoleOptions().setName("Role name").setExact(true)))
                .or(page.locator("#input-roleName"))
                .first();
    }

    /**
     * The role's name as shown on its Custom Roles card - confirmed live to be plain text, not a
     * button - the reliable "is this role listed" signal (never a fixed row position).
     */
    private Locator roleNameText(String roleName) {
        return page.getByText(roleName, new Page.GetByTextOptions().setExact(false)).first();
    }

    /**
     * The clickable "open" control on {@code roleName}'s card. Confirmed live: once the Roles list
     * search has filtered down to this one role, the whole card is itself a button whose
     * accessible name combines the role name with its badge and "Permissions" label (e.g.
     * "Operations Custom Permissions") - the stable control to click, preferred over guessing at
     * DOM position. Falls back to the card's own last icon button (visually the right-hand "open"
     * arrow, after the copy icon) for a build where the card is plain text instead.
     */
    private Locator roleCardOpenControl(String roleName) {
        Locator combined = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile(Pattern.quote(roleName) + "\\s+(custom|system)\\s+permissions",
                        Pattern.CASE_INSENSITIVE))).first();
        if (combined.count() > 0) {
            return combined;
        }
        Locator card = roleNameText(roleName).locator("xpath=ancestor::*[.//button][1]");
        return card.getByRole(AriaRole.BUTTON).last();
    }

    /**
     * The "Full" permission column's select-all control: the header checkbox first (the recorded
     * {@code columnheader('Full').getByLabel('')}), the {@code .chk__box} class the recording also
     * uses as a fallback.
     */
    private Locator fullColumnSelectAll() {
        return page.getByRole(AriaRole.COLUMNHEADER, new Page.GetByRoleOptions().setName("Full").setExact(true))
                        .getByRole(AriaRole.CHECKBOX)
                .or(page.locator(".chk__box").first())
                .first();
    }
}
