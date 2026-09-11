package com.vigilx.pages;

import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Users &amp; Roles -&gt; Groups: the full Custom Group lifecycle - create
 * ("Create Customized Group", a group name, the Email/Whatsapp/SMS notification options), verify
 * it is listed, rename it, verify the rename, delete it, and verify it is gone.
 *
 * <p>Reached after {@link RolesPage}'s role lifecycle. Follows the exact same page-object shape
 * and lessons learned from {@link RolesPage} (also confirmed live for this page - see the locator
 * Javadocs below): a {@link BasePage} subclass, locators kept here, small reusable action methods,
 * and the shared {@link SoakUiUtils#clickAndWaitForResponse} mechanism for every save/delete API
 * (no new API framework). Deliberately independent of {@link UsersRolesPage} / {@link RolesPage} -
 * it re-navigates to "Groups" itself rather than reusing/duplicating either class's internals,
 * exactly like they are each already independent of one another.
 *
 * <p>The group name is the identifier used to find the group both before and after the rename
 * (never a fixed row position, and the Groups list search is used so pagination cannot hide it); a
 * group id, when the create API's response exposes one, is captured and reused the same way
 * {@link RolesPage} captures a role id. Notifications are asserted by text via
 * {@link SoakUiUtils#readToastText}, never a fixed {@code #common-toast-N} id.
 *
 * <p>Contract: never throws into the caller. Every failure is logged and returned as
 * {@code false} so the soak continues to whatever runs after the group lifecycle.
 */
public class GroupsPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    private String lastCreatedGroupId;

    public GroupsPage(Page page) {
        super(page);
    }

    /** The group id captured from the create-group API response (may be blank). */
    public String lastCreatedGroupId() {
        return lastCreatedGroupId == null ? "" : lastCreatedGroupId;
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * The whole recorded lifecycle: {@link #navigateToGroups()} -&gt;
     * {@link #createGroup(GroupData)} -&gt; {@link #verifyGroupCreated(String)} -&gt;
     * {@link #updateGroup(String, GroupData)} -&gt; {@link #verifyGroupUpdated(String)} -&gt;
     * {@link #deleteGroup(String)} -&gt; {@link #verifyGroupDeleted(String)}.
     *
     * @param original the group to create
     * @param updated  the same group with the changed name (e.g. "Testing group update")
     */
    public boolean runGroupLifecycle(GroupData original, GroupData updated) {
        if (!navigateToGroups()) {
            return false;
        }
        boolean created = createGroup(original);
        boolean verifiedCreated = created && verifyGroupCreated(original.groupName());
        boolean updatedOk = verifiedCreated && updateGroup(original.groupName(), updated);
        boolean verifiedUpdated = updatedOk && verifyGroupUpdated(updated.groupName());
        boolean deleted = verifiedUpdated && deleteGroup(updated.groupName());
        boolean verifiedDeleted = deleted && verifyGroupDeleted(updated.groupName());
        System.out.println("[GROUPS] Lifecycle: created=" + created + " verifiedCreated=" + verifiedCreated
                + " updated=" + updatedOk + " verifiedUpdated=" + verifiedUpdated + " deleted=" + deleted
                + " verifiedDeleted=" + verifiedDeleted);
        return verifiedDeleted;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /** Opens Settings (only if not already open), "Users & Roles" (if required), then "Groups". */
    public boolean navigateToGroups() {
        Locator groupsTab = groupsTabLocator();
        boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(usersRolesLink().first())
                || SoakUiUtils.isVisibleQuietly(groupsTab.first())
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
                System.err.println("[GROUPS] 'Open settings' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        if (!SoakUiUtils.isVisibleQuietly(groupsTab.first())) {
            Locator usersRoles = usersRolesLink();
            if (SoakUiUtils.waitVisible(usersRoles, ELEMENT_TIMEOUT_MS)) {
                try {
                    usersRoles.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    waitAfterPageNavigation();
                } catch (Exception exception) {
                    System.err.println("[GROUPS] 'Users & Roles' could not be clicked: "
                            + SoakUiUtils.firstLine(exception.getMessage()));
                }
            }
        }

        if (!SoakUiUtils.waitVisible(groupsTab, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[GROUPS] 'Groups' tab not found.");
            return false;
        }
        try {
            groupsTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
            System.out.println("[GROUPS] Groups tab opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[GROUPS] 'Groups' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Clicks "Create Customized Group" and waits for its dialog (the group name field) to render. */
    public boolean clickCreateCustomizedGroup() {
        Locator create = createCustomizedGroupButton();
        if (!SoakUiUtils.waitVisible(create, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[GROUPS] 'Create Customized Group' button not found.");
            return false;
        }
        try {
            create.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[GROUPS] 'Create Customized Group' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean dialogOpen = SoakUiUtils.waitVisible(groupNameField(), ELEMENT_TIMEOUT_MS);
        System.out.println("[GROUPS] Create Customized Group dialog opened: " + (dialogOpen ? "YES" : "NO"));
        return dialogOpen;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    /**
     * {@link #clickCreateCustomizedGroup()} -&gt; {@link #enterGroupName(String)} -&gt;
     * {@link #setNotifications(GroupData)} -&gt; {@link #saveGroup(String, GroupData)}.
     */
    public boolean createGroup(GroupData data) {
        if (!clickCreateCustomizedGroup()) {
            return false;
        }
        boolean nameOk = enterGroupName(data.groupName());
        if (!nameOk) {
            System.err.println("[GROUPS]   Group name could not be entered; not saving.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
        boolean notificationsOk = setNotifications(data);
        boolean savedOk = saveGroup("Create group", data);
        System.out.println("[GROUPS] createGroup: name=" + nameOk + " notifications=" + notificationsOk
                + " saved=" + savedOk);
        return savedOk;
    }

    /** Fills the group name field (tolerant of the Create dialog's and the Edit panel's fields). */
    public boolean enterGroupName(String groupName) {
        Locator field = groupNameField();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[GROUPS]   Group name field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(groupName);
            return true;
        } catch (Exception exception) {
            System.err.println("[GROUPS]   Could not fill the group name: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Selects each requested notification option (Email / Whatsapp / SMS) dynamically: reads and
     * logs whether it is already checked before acting on it - never assumes it starts unselected.
     * {@link Locator#check} is itself a no-op on an already-checked box, so calling it
     * unconditionally afterward is safe either way. Verifies each one reads checked before
     * returning. A one-time warm-up click on the permissions panel's first checkbox-like control,
     * confirmed live to precede the very first checkbox interaction in this dialog, runs once
     * before the three checks below.
     */
    public boolean setNotifications(GroupData data) {
        Locator warmUp = page.locator(".chk__box").first();
        if (SoakUiUtils.isVisibleQuietly(warmUp)) {
            try {
                warmUp.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300);
            } catch (Exception ignored) {
                // Best effort - the per-checkbox check() calls below are the real action.
            }
        }

        boolean email = !data.email() || ensureNotificationChecked("Email");
        boolean whatsapp = !data.whatsapp() || ensureNotificationChecked("Whatsapp");
        boolean sms = !data.sms() || ensureNotificationChecked("SMS");
        System.out.println("[GROUPS]   Notifications set: email=" + email + " whatsapp=" + whatsapp
                + " sms=" + sms);
        return email && whatsapp && sms;
    }

    private boolean ensureNotificationChecked(String label) {
        Locator checkbox = notificationCheckbox(label);
        if (!SoakUiUtils.waitVisible(checkbox, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[GROUPS]   '" + label + "' notification control not found.");
            return false;
        }
        Boolean before = isCheckedQuietly(checkbox);
        System.out.println("[GROUPS]   '" + label + "' current state: "
                + (before == null ? "unknown" : (before ? "checked" : "unchecked")));
        if (Boolean.TRUE.equals(before)) {
            System.out.println("[GROUPS]   '" + label + "' already selected; leaving it.");
            return true;
        }

        // Confirmed live: the native checkbox input has no clickable bounding box at all (it sits
        // visually hidden behind its label's decorative ".chk__box" span, a custom-styled
        // checkbox) - even check(force=true) cannot click something with no box to click. The span
        // is the real, visible click target.
        Locator span = checkbox.locator("xpath=parent::label//span[contains(@class,'chk__box')]").first();
        Locator clickTarget = span.count() > 0 ? span : checkbox;
        try {
            clickTarget.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[GROUPS]   '" + label + "' could not be selected: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        page.waitForTimeout(300);
        Boolean now = isCheckedQuietly(checkbox);
        boolean selected = now == null || now;
        System.out.println("[GROUPS]   '" + label + "' selected: " + (selected ? "YES" : "NOT CONFIRMED"));
        return selected;
    }

    /**
     * Clicks "Save changes" and validates the background API: status is the hard signal; method,
     * endpoint, group id (once known) and the group name / notification flags in the
     * payload/response are logged as supporting evidence. Shared by create and update.
     */
    public boolean saveGroup(String context, GroupData data) {
        Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[GROUPS]   'Save changes' button not found.");
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, this::isGroupWriteResponse, 20000);
        boolean apiOk = validateGroupApi(response, context, data);
        boolean toastOk = waitForSuccessToast(context, toastBefore);
        SoakUiUtils.closeOpenDialogs(page);
        page.waitForTimeout(500);

        System.out.println("[GROUPS]   " + context + ": api=" + apiOk + " toast=" + toastOk);
        return apiOk;
    }

    /** Confirms {@code groupName} is now listed under Groups. */
    public boolean verifyGroupCreated(String groupName) {
        return verifyGroupListed(groupName);
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    /** {@link #openGroup(String)} -&gt; "Edit group" -&gt; {@link #enterGroupName(String)} -&gt; {@link #saveGroup}. */
    public boolean updateGroup(String groupIdentifier, GroupData updated) {
        if (!openGroup(groupIdentifier)) {
            return false;
        }
        Locator editGroup = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile("edit group", Pattern.CASE_INSENSITIVE))).first();
        if (SoakUiUtils.waitVisible(editGroup, 4000)) {
            try {
                editGroup.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(500);
            } catch (Exception exception) {
                System.err.println("[GROUPS]   'Edit group' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }
        if (!enterGroupName(updated.groupName())) {
            return false;
        }
        return saveGroup("Update group", updated);
    }

    /** Confirms the group now reads {@code updatedGroupName} under Groups. */
    public boolean verifyGroupUpdated(String updatedGroupName) {
        return verifyGroupListed(updatedGroupName);
    }

    /**
     * Finds {@code groupIdentifier}'s card under Groups by its name (never a fixed row position,
     * and filtered via the Groups list search so pagination cannot hide it) and opens it.
     */
    public boolean openGroup(String groupIdentifier) {
        filterGroupsList(groupIdentifier);
        Locator nameText = groupNameText(groupIdentifier);
        if (!SoakUiUtils.waitVisible(nameText, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[GROUPS]   Group '" + groupIdentifier + "' not found.");
            return false;
        }
        Locator open = groupCardOpenControl(groupIdentifier);
        try {
            if (SoakUiUtils.waitVisible(open, 4000)) {
                open.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } else {
                nameText.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            page.waitForTimeout(600);
            return true;
        } catch (Exception exception) {
            System.err.println("[GROUPS]   Group '" + groupIdentifier + "' could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /** {@link #openGroup(String)} -&gt; "Delete Group" -&gt; confirm "Delete" -&gt; validates the API + toast. */
    public boolean deleteGroup(String groupIdentifier) {
        if (!openGroup(groupIdentifier)) {
            return false;
        }
        Locator deleteGroupButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("delete group", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(deleteGroupButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[GROUPS]   'Delete Group' button not found.");
            return false;
        }
        try {
            deleteGroupButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[GROUPS]   'Delete Group' could not be clicked: "
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
            System.err.println("[GROUPS]   'Delete' confirmation button not found.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, confirmDelete, this::isGroupWriteResponse, 15000);
        boolean apiOk = validateGroupApi(response, "Delete group", null);
        boolean toastOk = waitForSuccessToast("Delete group", toastBefore);
        SoakUiUtils.closeOpenDialogs(page);
        page.waitForTimeout(800);

        System.out.println("[GROUPS]   Delete group: api=" + apiOk + " toast=" + toastOk);
        return apiOk;
    }

    /** Confirms {@code groupName} no longer appears under Groups. */
    public boolean verifyGroupDeleted(String groupName) {
        filterGroupsList(groupName);
        boolean stillListed = SoakUiUtils.isVisibleQuietly(groupNameText(groupName));
        System.out.println("[GROUPS]   Group '" + groupName + "' removed from Groups: "
                + (stillListed ? "NO (still listed)" : "YES"));
        return !stillListed;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean verifyGroupListed(String groupName) {
        filterGroupsList(groupName);
        boolean listed = SoakUiUtils.waitVisible(groupNameText(groupName), ELEMENT_TIMEOUT_MS);
        System.out.println("[GROUPS]   Group '" + groupName + "' listed under Groups: "
                + (listed ? "YES" : "NO"));
        return listed;
    }

    /**
     * Types {@code groupName} into the Groups list search and waits for it to filter - the list
     * may be paginated (as the Roles list already confirmed live), so a newly created or renamed
     * group is not necessarily on the first page without this. Confirmed live: unlike the Roles
     * list's "Search by role name", the Groups list search box's placeholder is just "Search".
     */
    private boolean filterGroupsList(String groupName) {
        Locator search = page.getByPlaceholder("Search", new Page.GetByPlaceholderOptions().setExact(true))
                .or(page.getByPlaceholder(Pattern.compile("search", Pattern.CASE_INSENSITIVE)))
                .first();
        if (!SoakUiUtils.waitVisible(search, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[GROUPS]   Groups list search box not found; checking the current page only.");
            return false;
        }
        try {
            search.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            search.fill("");
            search.fill(groupName);
            search.press("Enter");
            page.waitForTimeout(1500);
            return true;
        } catch (Exception exception) {
            System.err.println("[GROUPS]   Could not search the Groups list: "
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
                System.out.println("[GROUPS]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[GROUPS]   " + context + ": no notification observed within 6s.");
        return false;
    }

    /**
     * Logs and pass/fail-checks a group write response: HTTP status is the hard signal; the
     * method, endpoint, captured group id, the group name, and the Email/Whatsapp/SMS flags in the
     * payload/response are logged as supporting evidence. Captures the group id out of the
     * response the first time one is seen.
     */
    private boolean validateGroupApi(Response response, String context, GroupData data) {
        if (response == null) {
            System.err.println("[GROUPS]   " + context + ": no matching API response within timeout.");
            return false;
        }
        int status = response.status();
        boolean statusOk = status >= 200 && status < 300;
        String rawBody = SoakUiUtils.safeResponseBody(response);
        if (lastCreatedGroupId == null || lastCreatedGroupId.isBlank()) {
            lastCreatedGroupId = extractGroupId(rawBody);
        }
        String payload = SoakUiUtils.safeRequestBody(response).toLowerCase(Locale.ROOT);
        String body = rawBody.toLowerCase(Locale.ROOT);
        String id = lastCreatedGroupId().toLowerCase(Locale.ROOT);
        boolean idMatches = !id.isBlank()
                && (response.url().toLowerCase(Locale.ROOT).contains(id) || payload.contains(id) || body.contains(id));

        StringBuilder evidence = new StringBuilder();
        evidence.append("[GROUPS]   ").append(context).append(" API: ").append(SoakUiUtils.safeMethod(response))
                .append(' ').append(SoakUiUtils.shortPath(response.url())).append(" -> ").append(status)
                .append(" (").append(statusOk ? "PASS" : "FAIL").append(")")
                .append(" | groupId=").append(lastCreatedGroupId()).append(" (in request=").append(idMatches).append(")");
        if (data != null) {
            boolean nameMatches = payload.contains(data.groupName().toLowerCase(Locale.ROOT))
                    || body.contains(data.groupName().toLowerCase(Locale.ROOT));
            evidence.append(" | name '").append(data.groupName()).append("' present=").append(nameMatches);
            if (data.email()) {
                evidence.append(" | email present=").append(payload.contains("email"));
            }
            if (data.whatsapp()) {
                evidence.append(" | whatsapp present=").append(payload.contains("whatsapp"));
            }
            if (data.sms()) {
                evidence.append(" | sms present=").append(payload.contains("sms"));
            }
        }
        System.out.println(evidence);
        if (!statusOk) {
            System.err.println("[GROUPS]   " + context + " API FAILED (HTTP " + status + ").");
        }
        return statusOk;
    }

    /** Best-effort group id out of a JSON body: the value of an "id"/"groupId"/"group_id"/"_id" key. */
    private String extractGroupId(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "\"(?:group_?id|_id|id)\"\\s*:\\s*\"?([A-Za-z0-9_-]{2,64})\"?",
                Pattern.CASE_INSENSITIVE).matcher(jsonBody);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** True for the write request that backs a Custom Group create/update/delete. Broad on purpose. */
    private boolean isGroupWriteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                    || "DELETE".equals(method);
            return write && url.contains("group");
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

    private Locator groupsTabLocator() {
        return page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Groups").setExact(true)).first();
    }

    private Locator createCustomizedGroupButton() {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile("create customi[sz]ed group", Pattern.CASE_INSENSITIVE))).first();
    }

    /**
     * The group-name field. Mirrors {@link RolesPage}'s confirmed-live finding for its own role
     * name field (an input with no {@code type} attribute, sitting next to a "Search by ... name"
     * box whose placeholder also contains the substring "group name") - every alternative here is
     * therefore an <em>exact</em> match, never a loose "contains" pattern.
     */
    private Locator groupNameField() {
        return page.getByPlaceholder("Enter Group name", new Page.GetByPlaceholderOptions().setExact(true))
                .or(page.getByRole(AriaRole.TEXTBOX,
                        new Page.GetByRoleOptions().setName("Group name").setExact(true)))
                .or(page.locator("#input-groupName"))
                .first();
    }

    /** The Email / Whatsapp / SMS notification checkbox, matched by its own label text. */
    private Locator notificationCheckbox(String label) {
        return page.getByRole(AriaRole.CHECKBOX,
                new Page.GetByRoleOptions().setName(label).setExact(true)).first();
    }

    /**
     * The group's name as shown on its Groups card - confirmed live (for the equivalent Roles
     * card) to be reliable as either plain text or a combined-name button; matching by text alone
     * is the stable "is this group listed" signal either way (never a fixed row position).
     */
    private Locator groupNameText(String groupName) {
        return page.getByText(groupName, new Page.GetByTextOptions().setExact(false)).first();
    }

    /**
     * The clickable "open" control on {@code groupName}'s card. Mirrors {@link RolesPage}'s
     * confirmed-live finding for its own cards: once the Groups list search has filtered to this
     * one group, the whole card may itself be a button whose accessible name combines the group
     * name with a badge/label - tried first - falling back to the card's own last icon button
     * (visually the right-hand "open" control) for a build where the card is plain text instead.
     */
    private Locator groupCardOpenControl(String groupName) {
        Locator combined = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile(Pattern.quote(groupName) + "\\s+(custom|system)?",
                        Pattern.CASE_INSENSITIVE))).first();
        if (combined.count() > 0) {
            return combined;
        }
        Locator card = groupNameText(groupName).locator("xpath=ancestor::*[.//button][1]");
        return card.getByRole(AriaRole.BUTTON).last();
    }
}
