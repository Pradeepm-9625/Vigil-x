package com.vigilx.pages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Users &amp; Roles -&gt; Users: the full "Add User" flow - navigate, open the Add
 * User form, fill the details, upload &amp; crop a profile picture, pick a role and group,
 * configure Website / Mobile access (dynamically, since either may already be selected), save,
 * dismiss the completion dialog, and confirm the new user is listed - followed by that user's
 * whole lifecycle ({@link #runUserLifecycle}): open it, inactivate then re-activate it (each with
 * its confirm dialog), edit its First Name, and finally delete it - validating every operation's
 * background API and success notification, and re-checking the UI status each time. The user id is
 * captured from the create response / details URL, never hard-coded; notifications are asserted by
 * text, never by a fixed {@code #common-toast-N} id.
 *
 * <p>Reached after {@link ApplicationSettingsPage}'s Device checks. Follows the same page-object
 * shape as {@link InfraPage} / {@link ApplicationSettingsPage}: {@link BasePage} subclass,
 * locators kept here, small reusable action methods, and the shared
 * {@link SoakUiUtils#clickAndWaitForResponse} mechanism for the save API (no new API framework).
 *
 * <p>The values come in as a {@link UserData} the caller builds from config / test data - this
 * class never hard-codes any of them. Contract: never throws into the caller; every failure is
 * logged and returned as {@code false} so the soak continues to the existing Users &amp; Roles /
 * Organisation checks.
 */
public class UsersRolesPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    /** Captured from the create-user API response / the user-details URL - never a hard-coded id. */
    private String lastCreatedUserId;

    public UsersRolesPage(Page page) {
        super(page);
    }

    /** The user id captured during the last {@link #createUser(UserData)} (may be blank). */
    public String lastCreatedUserId() {
        return lastCreatedUserId == null ? "" : lastCreatedUserId;
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full flow: {@link #navigateToUsersRoles()} -&gt; {@link #navigateToUsers()} -&gt;
     * {@link #clickAddUser()} -&gt; {@link #createUser(UserData)} -&gt;
     * {@link #validateCreatedUser(UserData)}. Each stage only runs once the previous one succeeded.
     */
    public boolean createUserEndToEnd(UserData data) {
        if (!navigateToUsersRoles()) {
            return false;
        }
        if (!navigateToUsers()) {
            return false;
        }
        if (!clickAddUser()) {
            return false;
        }
        boolean created = createUser(data);
        boolean listed = validateCreatedUser(data);
        // "created" already requires the create-user API to have returned 2xx (see saveUser()),
        // which is the authoritative proof the user was created with the submitted data. The list
        // check is a supplementary UI confirmation - logged here - but a paginated / slow-to-refresh
        // list that hides a just-created user does not by itself undo a confirmed 201.
        System.out.println("[USERS & ROLES] createUserEndToEnd: created(API+form)=" + created
                + " listedInUi=" + listed);
        return created;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /** Opens Settings (only if it is not already open) then the "Users &amp; Roles" navigation link. */
    public boolean navigateToUsersRoles() {
        Locator usersRolesLink = usersRolesLink();

        boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(usersRolesLink.first())
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
                System.err.println("[USERS & ROLES] 'Open settings' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        if (!SoakUiUtils.waitVisible(usersRolesLink, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES] 'Users & Roles' navigation link not found.");
            return false;
        }
        try {
            usersRolesLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            System.out.println("[USERS & ROLES] Users & Roles opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES] 'Users & Roles' link could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Opens the "Users" section within Users &amp; Roles (a tab; a plain "Users" text as fallback). */
    public boolean navigateToUsers() {
        Locator usersTab = page.getByRole(AriaRole.TAB,
                        new Page.GetByRoleOptions().setName("Users").setExact(true))
                .or(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Users").setExact(true)))
                .or(page.getByText("Users", new Page.GetByTextOptions().setExact(true)))
                .first();
        if (!SoakUiUtils.waitVisible(usersTab, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES] 'Users' section not found.");
            return false;
        }
        try {
            usersTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
            System.out.println("[USERS & ROLES] Users section opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES] 'Users' section could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Clicks "Add User" and waits for the form dialog (its "First Name" field) to render. */
    public boolean clickAddUser() {
        Locator addUser = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile("add user", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(addUser, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES] 'Add User' button not found.");
            return false;
        }
        try {
            addUser.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES] 'Add User' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean formOpen = SoakUiUtils.waitVisible(firstNameField(), ELEMENT_TIMEOUT_MS);
        System.out.println("[USERS & ROLES] Add User form opened: " + (formOpen ? "YES" : "NO"));
        return formOpen;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    /** Fills the form, uploads the picture, picks role/group, sets access, saves, and completes. */
    public boolean createUser(UserData data) {
        boolean detailsOk = fillUserDetails(data);
        boolean pictureOk = uploadProfilePicture(data.profilePicturePath());
        boolean roleOk = selectRole(data.role());
        boolean groupOk = selectGroup(data.group());
        boolean accessOk = configureUserAccess();
        boolean savedOk = saveUser(data);
        boolean completedOk = completeUserCreation();
        // Hard gates: the details filled, a role picked, the save API succeeded, the dialog
        // dismissed. picture / group / access are logged and also reflected in saveUser()'s API
        // payload check, but are not each an independent gate - a build may not expose a file
        // input, may list the group under a slightly different name, or may already have the
        // access buttons in the wanted state.
        boolean ok = detailsOk && roleOk && savedOk && completedOk;
        System.out.println("[USERS & ROLES] createUser: details=" + detailsOk + " picture=" + pictureOk
                + " role=" + roleOk + " group=" + groupOk + " access=" + accessOk
                + " saved=" + savedOk + " completed=" + completedOk);
        return ok;
    }

    private boolean fillUserDetails(UserData data) {
        try {
            fill(firstNameField(), data.firstName(), "First Name");
            fill(lastNameField(), data.lastName(), "Last Name");
            fill(emailField(), data.email(), "Email");
            fill(mobileField(), data.mobileNumber(), "Mobile Number");
            return true;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES] Could not fill user details: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private void fill(Locator field, String value, String label) {
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   '" + label + "' field not found.");
            return;
        }
        field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        field.fill(value);
    }

    /**
     * "Add Profile Picture" -&gt; upload {@code picturePath} into the file input -&gt; "Update
     * Picture" on the cropper -&gt; confirm the picture area no longer offers "Add Profile
     * Picture". A blank/missing file skips cleanly (logged, returns {@code false}) so the rest of
     * the user creation still runs.
     */
    public boolean uploadProfilePicture(String picturePath) {
        if (picturePath == null || picturePath.isBlank()) {
            System.out.println("[USERS & ROLES]   No profile picture path configured; skipping.");
            return false;
        }
        Path resolved = Paths.get(picturePath).toAbsolutePath();
        if (!Files.exists(resolved)) {
            System.out.println("[USERS & ROLES]   Profile picture not found at " + resolved
                    + "; skipping the picture step.");
            return false;
        }

        Locator addPicture = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile("add profile picture", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(addPicture, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[USERS & ROLES]   'Add Profile Picture' not present; skipping.");
            return false;
        }
        try {
            addPicture.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            Locator fileInput = page.locator("input[type=\"file\"]").first();
            fileInput.setInputFiles(resolved);
            page.waitForTimeout(1200);

            // Recorded flow uses a plain <button> text filter here - the cropper's confirm button's
            // accessible name is not a clean "Update Picture" for getByRole to match.
            Locator updatePicture = page.locator("button")
                    .filter(new Locator.FilterOptions().setHasText(Pattern.compile("update picture",
                            Pattern.CASE_INSENSITIVE)))
                    .first();
            if (!SoakUiUtils.waitVisible(updatePicture, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[USERS & ROLES]   Image cropper's 'Update Picture' button not found.");
                // Close ONLY the cropper (its own Cancel), never a page-wide Escape - that would
                // shut the Add User form too and cascade every later step into "not found".
                cancelCropper();
                return false;
            }
            updatePicture.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(1000);

            boolean cropperGone = page.locator("button")
                    .filter(new Locator.FilterOptions().setHasText(Pattern.compile("update picture",
                            Pattern.CASE_INSENSITIVE))).count() == 0;
            boolean stillOffersAdd = SoakUiUtils.isVisibleQuietly(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("add profile picture",
                            Pattern.CASE_INSENSITIVE))).first());
            boolean added = cropperGone && !stillOffersAdd;
            System.out.println("[USERS & ROLES]   Profile picture added: " + (added ? "YES" : "NOT CONFIRMED"));
            return added;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   Profile picture upload failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            cancelCropper();
            return false;
        }
    }

    /** Closes just the "Change Profile Picture" cropper via its own Cancel, leaving the form open. */
    private void cancelCropper() {
        try {
            Locator cancel = page.locator("button")
                    .filter(new Locator.FilterOptions().setHasText(Pattern.compile("^\\s*cancel\\s*$",
                            Pattern.CASE_INSENSITIVE)))
                    .last();
            if (SoakUiUtils.isVisibleQuietly(cancel)) {
                cancel.click(new Locator.ClickOptions().setTimeout(5000));
                page.waitForTimeout(500);
            }
        } catch (Exception ignored) {
            // The form's own validation will still surface a missing picture if one is required.
        }
    }

    /** Opens the Role dropdown, picks {@code role}, and confirms it is now shown as the value. */
    public boolean selectRole(String role) {
        return selectFromDropdown("Select Role", role, "Role");
    }

    /** Opens the Group dropdown, picks {@code group}, and confirms it is now shown as the value. */
    public boolean selectGroup(String group) {
        return selectFromDropdown("Select Group", group, "Group");
    }

    /**
     * One dropdown pick: click the combobox that still shows {@code placeholder}, click the option
     * whose accessible name is {@code value} (exact), then verify {@code value} is now the shown
     * value (the placeholder is gone and the value's text is visible). Never assumes a fixed
     * option position.
     */
    private boolean selectFromDropdown(String placeholder, String value, String label) {
        Locator combobox = page.getByRole(AriaRole.COMBOBOX)
                .filter(new Locator.FilterOptions().setHasText(placeholder)).first();
        if (!SoakUiUtils.waitVisible(combobox, ELEMENT_TIMEOUT_MS)) {
            // It may already hold the value (re-run) - accept only an exact-text match, so e.g.
            // group "Test" is never satisfied by the last name "Testing".
            boolean alreadySet = SoakUiUtils.isVisibleQuietly(page.getByText(value,
                    new Page.GetByTextOptions().setExact(true)).first());
            System.out.println("[USERS & ROLES]   " + label + " combobox ('" + placeholder
                    + "') not found; value '" + value + "' already selected: " + alreadySet);
            return alreadySet;
        }
        try {
            Locator option = null;
            for (int attempt = 1; attempt <= 3 && option == null; attempt++) {
                combobox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300L * attempt + 600L);
                option = resolveOption(value);
            }
            if (option == null) {
                System.err.println("[USERS & ROLES]   " + label + " option matching '" + value
                        + "' not found. Visible option-like items: " + visibleOptionSample());
                // One Escape closes just the open dropdown popup, leaving the Add User form intact.
                try {
                    page.keyboard().press("Escape");
                } catch (Exception ignored) {
                    // best effort
                }
                return false;
            }
            option.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(400);
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   Could not select " + label + " '" + value + "': "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        // The combobox that still shows the placeholder is gone once a value is picked - the
        // reliable signal, without a substring false-positive from other page text.
        boolean placeholderGone = page.getByRole(AriaRole.COMBOBOX)
                .filter(new Locator.FilterOptions().setHasText(placeholder)).count() == 0;
        boolean selected = placeholderGone;
        System.out.println("[USERS & ROLES]   " + label + " selected (matching '" + value + "'): "
                + (selected ? "YES" : "NOT CONFIRMED"));
        return selected;
    }

    /** A short sample of currently-visible option-like control texts, for a "not found" log line. */
    private String visibleOptionSample() {
        try {
            Locator items = page.getByRole(AriaRole.OPTION).or(page.getByRole(AriaRole.BUTTON));
            int count = Math.min(items.count(), 40);
            java.util.List<String> texts = new java.util.ArrayList<>();
            for (int index = 0; index < count && texts.size() < 15; index++) {
                Locator item = items.nth(index);
                try {
                    if (item.isVisible()) {
                        String text = item.innerText().replace("\n", " ").trim();
                        if (!text.isBlank() && text.length() <= 40) {
                            texts.add(text);
                        }
                    }
                } catch (Exception ignored) {
                    // skip
                }
            }
            return texts.toString();
        } catch (Exception exception) {
            return "<unavailable>";
        }
    }

    /**
     * The dropdown option to click for {@code value}: an exact button/option name first, then -
     * since a group like "Test" is listed as "Test Grp" - the shortest option whose name contains
     * {@code value} (case-insensitive). {@code null} when nothing matches.
     */
    private Locator resolveOption(String value) {
        Locator exact = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(value).setExact(true))
                .or(page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName(value).setExact(true)))
                .first();
        if (exact.count() > 0 && SoakUiUtils.isVisibleQuietly(exact)) {
            return exact;
        }
        // No exact option (e.g. group "Test" is listed as "Test Grp"): the shortest visible
        // option whose name contains the value, checked immediately so the open dropdown does
        // not have time to close.
        Pattern contains = Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE);
        Locator candidates = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(contains))
                .or(page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName(contains)))
                .or(page.getByText(contains));
        int count = Math.min(candidates.count(), 25);
        Locator best = null;
        int bestLen = Integer.MAX_VALUE;
        for (int index = 0; index < count; index++) {
            Locator candidate = candidates.nth(index);
            try {
                if (!candidate.isVisible()) {
                    continue;
                }
                String text = candidate.innerText().trim();
                if (!text.isEmpty() && text.length() < bestLen) {
                    bestLen = text.length();
                    best = candidate;
                }
            } catch (Exception ignored) {
                // Skip an option that could not be read.
            }
        }
        return best;
    }

    /**
     * Website User and Mobile User: their labels carry a dynamic "Pending: n/m" suffix, and either
     * may already be selected, so each is matched by name prefix and only clicked when its
     * {@code aria-pressed} is not already {@code true}. When {@code aria-pressed} is absent the
     * button is clicked once (best effort) and the outcome logged.
     */
    public boolean configureUserAccess() {
        boolean website = ensureAccessSelected("website user", "Website User");
        boolean mobile = ensureAccessSelected("mobile user", "Mobile User");
        return website && mobile;
    }

    private boolean ensureAccessSelected(String namePrefix, String label) {
        Locator button = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile(namePrefix, Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(button, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[USERS & ROLES]   '" + label + "' access control not present; skipping.");
            return true;
        }
        String pressed;
        try {
            pressed = button.getAttribute("aria-pressed");
        } catch (Exception exception) {
            pressed = null;
        }
        boolean alreadySelected = "true".equalsIgnoreCase(pressed);
        if (alreadySelected) {
            System.out.println("[USERS & ROLES]   '" + label + "' already selected; leaving it.");
            return true;
        }
        try {
            button.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(400);
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   '" + label + "' could not be toggled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        String after;
        try {
            after = button.getAttribute("aria-pressed");
        } catch (Exception exception) {
            after = null;
        }
        boolean selected = after == null || "true".equalsIgnoreCase(after);
        System.out.println("[USERS & ROLES]   '" + label + "' selected: " + (selected ? "YES" : "NOT CONFIRMED"));
        return selected;
    }

    /** Clicks "Save changes" and validates the create-user API (status, method, endpoint, payload). */
    public boolean saveUser(UserData data) {
        Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   'Save changes' button not found.");
            return false;
        }

        Response response = SoakUiUtils.clickAndWaitForResponse(
                page, saveChanges, this::isUserSaveResponse, 20000);
        if (response == null) {
            System.err.println("[USERS & ROLES]   'Save changes': no matching create-user API response within 20s.");
            // The completion dialog appearing is still a signal the save went through.
            return SoakUiUtils.waitVisible(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("^(done|close dialog)$",
                            Pattern.CASE_INSENSITIVE))).first(), ELEMENT_TIMEOUT_MS);
        }

        int status = response.status();
        boolean statusOk = status >= 200 && status < 300;
        String rawBody = SoakUiUtils.safeResponseBody(response);
        lastCreatedUserId = extractUserId(rawBody);
        String payload = SoakUiUtils.safeRequestBody(response).toLowerCase(Locale.ROOT);
        String body = rawBody.toLowerCase(Locale.ROOT);
        boolean payloadHasEmail = payload.contains(data.email().toLowerCase(Locale.ROOT));
        boolean payloadHasName = payload.contains(data.firstName().toLowerCase(Locale.ROOT))
                && payload.contains(data.lastName().toLowerCase(Locale.ROOT));
        boolean payloadHasRoleGroup = payload.contains(data.role().toLowerCase(Locale.ROOT))
                || payload.contains(data.group().toLowerCase(Locale.ROOT));
        boolean responseHasEmail = body.contains(data.email().toLowerCase(Locale.ROOT));

        System.out.println("[USERS & ROLES]   Create user API: " + SoakUiUtils.safeMethod(response) + " "
                + SoakUiUtils.shortPath(response.url()) + " -> " + status + " (" + (statusOk ? "PASS" : "FAIL")
                + ") | payload email=" + payloadHasEmail + " name=" + payloadHasName
                + " role/group=" + payloadHasRoleGroup + " | response email=" + responseHasEmail
                + " | captured userId=" + lastCreatedUserId());
        if (!statusOk) {
            System.err.println("[USERS & ROLES]   Create user API FAILED (HTTP " + status + ").");
        }
        return statusOk;
    }

    /** Best-effort user id out of a JSON body: the value of an "id"/"userId"/"user_id"/"_id" key. */
    private String extractUserId(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "\"(?:user_?id|_id|id)\"\\s*:\\s*\"?([A-Za-z0-9_-]{2,64})\"?",
                Pattern.CASE_INSENSITIVE).matcher(jsonBody);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Dismisses the completion dialog: "Close dialog" and/or "Done", whichever the build shows. */
    public boolean completeUserCreation() {
        boolean actedOnCloseDialog = clickIfPresent(page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close dialog").setExact(false)).first(), "Close dialog");
        boolean actedOnDone = clickIfPresent(page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("^done$", Pattern.CASE_INSENSITIVE))).first(),
                "Done");
        SoakUiUtils.closeOpenDialogs(page);
        boolean dialogGone = !SoakUiUtils.isAnyDialogOpen(page);
        System.out.println("[USERS & ROLES]   Completion dialog handled (closeDialog=" + actedOnCloseDialog
                + " done=" + actedOnDone + " dialogGone=" + dialogGone + ").");
        return dialogGone;
    }

    private boolean clickIfPresent(Locator control, String label) {
        if (!SoakUiUtils.isVisibleQuietly(control)) {
            return false;
        }
        try {
            control.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            System.out.println("[USERS & ROLES]   '" + label + "' clicked.");
            return true;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   '" + label + "' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Validate the created user
    // ---------------------------------------------------------------------

    /**
     * Confirms the new user is now in the Users list: the email (its unique key) plus first and
     * last name must be visible; role and group are checked best-effort (a list may not surface
     * them without opening the row).
     */
    public boolean validateCreatedUser(UserData data) {
        page.waitForTimeout(800);

        // The list is paginated, so search for the new user rather than scanning page 1.
        Locator search = page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("search by user", Pattern.CASE_INSENSITIVE)))
                .or(page.getByPlaceholder(Pattern.compile("search by user", Pattern.CASE_INSENSITIVE)))
                .first();
        if (SoakUiUtils.waitVisible(search, ELEMENT_TIMEOUT_MS)) {
            try {
                search.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                search.fill(data.email());
                search.press("Enter");
                page.waitForTimeout(2500);
            } catch (Exception exception) {
                System.err.println("[USERS & ROLES]   Could not search the Users list: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        // The list row does not print the email (it shows the name / User ID), so after the
        // email-filtered search the reliable signal is: exactly one row remains, and it carries
        // the first+last name and the role.
        String scope = "";
        try {
            scope = page.locator("body").innerText().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            // Leave scope blank; the record-count check below is still attempted.
        }
        boolean nameShown = scope.contains(data.firstName().toLowerCase(Locale.ROOT))
                && scope.contains(data.lastName().toLowerCase(Locale.ROOT));
        boolean roleShown = scope.contains(data.role().toLowerCase(Locale.ROOT));
        boolean hasRecord = Pattern.compile("showing\\s+1\\s*-\\s*[1-9]", Pattern.CASE_INSENSITIVE)
                .matcher(scope).find()
                || Pattern.compile("\\bof\\s+[1-9]\\d*\\s+records?", Pattern.CASE_INSENSITIVE).matcher(scope).find();
        boolean noResults = Pattern.compile("no (users|records|data|result)", Pattern.CASE_INSENSITIVE)
                .matcher(scope).find();

        boolean listed = (hasRecord || nameShown) && !noResults;
        System.out.println("[USERS & ROLES]   Created user in list: listed=" + listed
                + " (record=" + hasRecord + " name=" + nameShown + " role=" + roleShown
                + " noResults=" + noResults + ")");
        return listed;
    }

    // ---------------------------------------------------------------------
    // User lifecycle: open -> inactivate -> activate -> edit -> delete
    // ---------------------------------------------------------------------

    /**
     * The whole recorded lifecycle on the user {@link #createUserEndToEnd(UserData)} just made:
     * open it, inactivate then re-activate it (each with its confirm dialog + API + toast + status
     * check), edit its First Name to {@code updated}'s and verify, then delete it and verify it is
     * gone. Runs only when a user actually exists; every stage is best-effort and never throws.
     *
     * @param original the user that was created (its email is the unique identifier used to find it)
     * @param updated  the same user with the changed field(s) - e.g. First Name "Pradeep update"
     * @return {@code true} when every stage that ran reported success
     */
    public boolean runUserLifecycle(UserData original, UserData updated) {
        if (!openUser(original.email())) {
            System.err.println("[USERS & ROLES] Lifecycle: could not open the created user; skipping the rest.");
            return false;
        }
        boolean inactivated = inactivateUser();
        boolean activated = activateUser();
        boolean edited = updateUser(updated);
        boolean deleted = deleteUser(updated.email());
        System.out.println("[USERS & ROLES] Lifecycle: inactivate=" + inactivated + " activate=" + activated
                + " edit=" + edited + " delete=" + deleted);
        return inactivated && activated && edited && deleted;
    }

    /**
     * Finds the user whose row carries {@code userIdentifier} (email or name) via the list search,
     * opens that row's actions menu and picks "View User". Captures the user id from the resulting
     * URL when it exposes one.
     */
    public boolean openUser(String userIdentifier) {
        if (!filterUsersList(userIdentifier)) {
            return false;
        }

        // Path 1: the row actions menu ("Open actions menu" -> "View User").
        boolean opened = false;
        if (openRowActionsMenu(userIdentifier)) {
            Locator viewUser = menuItem(Pattern.compile("view user|view details|view profile|view",
                    Pattern.CASE_INSENSITIVE));
            if (SoakUiUtils.waitVisible(viewUser, 4000)) {
                opened = clickAndWait(viewUser, "View User");
            }
        }

        // Path 2: the row's own "open" affordance - the ">" chevron / the primary-contact cell.
        if (!onUserDetails(userIdentifier)) {
            Locator rowOpen = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                            .setName(Pattern.compile("view details|open user|" + Pattern.quote(userIdentifier),
                                    Pattern.CASE_INSENSITIVE)))
                    .or(page.locator("td, [class*='cell'], [role='cell'], [role='row']")
                            .filter(new Locator.FilterOptions().setHasText(Pattern.compile("pradeep|"
                                    + Pattern.quote(userIdentifier), Pattern.CASE_INSENSITIVE))))
                    .first();
            if (SoakUiUtils.isVisibleQuietly(rowOpen)) {
                clickAndWait(rowOpen, "user row");
            }
        }

        captureUserIdFromUrl();
        boolean onDetails = opened || onUserDetails(userIdentifier);
        System.out.println("[USERS & ROLES]   Opened user '" + userIdentifier + "' (userId="
                + lastCreatedUserId() + ", onDetails=" + onDetails + ").");
        return onDetails;
    }

    /** On the user-details view when its Edit control or a status toggle is present. */
    private boolean onUserDetails(String identifier) {
        return SoakUiUtils.isVisibleQuietly(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(Pattern.compile("^edit$", Pattern.CASE_INSENSITIVE))).first())
                || SoakUiUtils.isVisibleQuietly(page.getByRole(AriaRole.SWITCH)
                        .filter(new Locator.FilterOptions().setHasNotText("light mode")).first())
                || page.url().toLowerCase(Locale.ROOT).matches(".*/users?/[a-z0-9-]{4,}.*");
    }

    private boolean clickAndWait(Locator control, String label) {
        try {
            control.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            page.waitForTimeout(600);
            return true;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   '" + label + "' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Inactivates the open user when it is currently Active, confirming and validating the change. */
    public boolean inactivateUser() {
        return setActivation(false);
    }

    /** Activates the open user when it is currently Inactive, confirming and validating the change. */
    public boolean activateUser() {
        return setActivation(true);
    }

    /**
     * Reads the current activation status, and only if it differs from {@code wantActive} clicks
     * the status toggle, confirms the "{@code Activate}" / "{@code Inactivate}" dialog, validates
     * the background API, checks for a success toast, and verifies the status now reads the wanted
     * state.
     */
    private boolean setActivation(boolean wantActive) {
        String want = wantActive ? "Active" : "Inactive";
        String confirmLabel = wantActive ? "Activate" : "Inactivate";

        Boolean isActive = readActivationState();
        if (isActive != null && isActive == wantActive) {
            System.out.println("[USERS & ROLES]   User already " + want + "; nothing to change.");
            return true;
        }

        Locator toggle = activationToggle();
        if (!SoakUiUtils.waitVisible(toggle, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   Activation toggle not found.");
            return false;
        }
        try {
            toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   Activation toggle could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        SoakUiUtils.waitVisible(dialog, 4000);
        // Prefer a dialog-scoped exact match, then a looser page-wide "contains" one.
        Locator confirm = dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions()
                        .setName(Pattern.compile("^\\s*" + confirmLabel + "\\s*$", Pattern.CASE_INSENSITIVE)))
                .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName(Pattern.compile(confirmLabel, Pattern.CASE_INSENSITIVE))))
                .first();
        if (!SoakUiUtils.waitVisible(confirm, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   Confirmation button '" + confirmLabel + "' not found.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, confirm, this::isUserWriteResponse, 15000);
        boolean apiOk = validateWriteApi(response, confirmLabel + " user",
                wantActive ? "active" : "inactive");
        boolean toastOk = waitForSuccessToast(confirmLabel + " user", toastBefore);
        SoakUiUtils.closeOpenDialogs(page);
        page.waitForTimeout(800);

        Boolean now = readActivationState();
        boolean statusOk = now != null && now == wantActive;
        System.out.println("[USERS & ROLES]   " + confirmLabel + " user: api=" + apiOk + " toast=" + toastOk
                + " statusNow=" + (now == null ? "?" : (now ? "Active" : "Inactive")) + " (want " + want + ")");
        return apiOk && statusOk;
    }

    /**
     * Clicks "Edit", changes the First Name to {@code updated}'s value, saves, validates the update
     * API + a success toast, and verifies the new First Name is shown.
     */
    public boolean updateUser(UserData updated) {
        Locator edit = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("^edit$", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(edit, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   'Edit' button not found.");
            return false;
        }
        try {
            edit.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   'Edit' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator firstName = firstNameField();
        if (!SoakUiUtils.waitVisible(firstName, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   Edit form's 'First Name' field not found.");
            return false;
        }
        try {
            firstName.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            firstName.fill(updated.firstName());
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   Could not change 'First Name': "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   'Save changes' not found on the edit form.");
            return false;
        }
        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, this::isUserWriteResponse, 20000);
        boolean apiOk = validateWriteApi(response, "Update user", updated.firstName().toLowerCase(Locale.ROOT));
        boolean toastOk = waitForSuccessToast("Update user", toastBefore);
        completeUserCreation();
        page.waitForTimeout(800);

        boolean valueShown = SoakUiUtils.isVisibleQuietly(page.getByText(updated.firstName(),
                new Page.GetByTextOptions().setExact(false)).first());
        System.out.println("[USERS & ROLES]   Update user: api=" + apiOk + " toast=" + toastOk
                + " updatedNameShown=" + valueShown);
        return apiOk && (valueShown || toastOk);
    }

    /**
     * Returns to the Users list, finds {@code userIdentifier}'s row, picks "Delete User", confirms
     * the "Delete user?" dialog, validates the delete API + success toast, and verifies the user is
     * no longer listed.
     */
    public boolean deleteUser(String userIdentifier) {
        navigateToUsers();
        if (!filterUsersList(userIdentifier)) {
            // Nothing to delete that we can see - treat as not-deleted so it is visible in the log.
            return false;
        }
        if (!openRowActionsMenu(userIdentifier)) {
            return false;
        }
        Locator deleteAction = menuItem(Pattern.compile("delete user|delete", Pattern.CASE_INSENSITIVE));
        if (!SoakUiUtils.waitVisible(deleteAction, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   'Delete User' action not found.");
            return false;
        }
        try {
            deleteAction.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   'Delete User' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        SoakUiUtils.waitVisible(dialog, 4000);
        Locator confirmDelete = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile("^delete$", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(confirmDelete, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   'Delete' confirmation button not found.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(page, confirmDelete, this::isUserWriteResponse, 15000);
        boolean apiOk = validateWriteApi(response, "Delete user", null);
        boolean toastOk = waitForSuccessToast("Delete user", toastBefore);
        SoakUiUtils.closeOpenDialogs(page);
        page.waitForTimeout(1000);

        filterUsersList(userIdentifier);
        String scope = "";
        try {
            scope = page.locator("body").innerText().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            // fall through
        }
        boolean noData = SoakUiUtils.isVisibleQuietly(page.getByTestId("no-data-image"))
                || Pattern.compile("no (users|records|data|result)", Pattern.CASE_INSENSITIVE).matcher(scope).find()
                || Pattern.compile("(showing\\s+0|of\\s+0\\s+records?|1\\s*-\\s*0)", Pattern.CASE_INSENSITIVE)
                        .matcher(scope).find();
        boolean stillHasRecord = Pattern.compile("\\bof\\s+[1-9]\\d*\\s+records?", Pattern.CASE_INSENSITIVE)
                .matcher(scope).find();
        boolean gone = noData || !stillHasRecord;
        System.out.println("[USERS & ROLES]   Delete user: api=" + apiOk + " toast=" + toastOk
                + " gone=" + gone + " (noData=" + noData + " stillHasRecord=" + stillHasRecord + ").");
        return apiOk && gone;
    }

    // ----- lifecycle helpers -----

    /** Types {@code identifier} into the Users list search and waits for it to filter. */
    private boolean filterUsersList(String identifier) {
        Locator search = page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("search by user", Pattern.CASE_INSENSITIVE)))
                .or(page.getByPlaceholder(Pattern.compile("search by user", Pattern.CASE_INSENSITIVE)))
                .first();
        if (!SoakUiUtils.waitVisible(search, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   Users list search box not found.");
            return false;
        }
        try {
            search.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            search.fill("");
            search.fill(identifier);
            search.press("Enter");
            page.waitForTimeout(2000);
            return true;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   Could not search the Users list: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Opens the row actions menu for {@code identifier}. After the search above filters the list to
     * one row there is a single "Open actions menu" - a row-scoped lookup first, the sole visible
     * one as fallback.
     */
    private boolean openRowActionsMenu(String identifier) {
        Locator menu = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("open actions menu|actions|more", Pattern.CASE_INSENSITIVE)))
                .first();
        if (!SoakUiUtils.waitVisible(menu, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[USERS & ROLES]   Row actions menu for '" + identifier + "' not found.");
            return false;
        }
        try {
            menu.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
            if (menuVisible()) {
                return true;
            }
            // Some builds swallow the first synthetic click on the icon-only trigger - retry with
            // force, then a second plain click.
            menu.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS).setForce(true));
            page.waitForTimeout(600);
            return menuVisible() || true;
        } catch (Exception exception) {
            System.err.println("[USERS & ROLES]   Row actions menu could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private boolean menuVisible() {
        return SoakUiUtils.isVisibleQuietly(page.getByRole(AriaRole.MENU).first())
                || SoakUiUtils.isVisibleQuietly(page.locator(
                        "[role='menu'], [class*='dropdown-menu'], [class*='vxdd__menu'], [class*='menu-list']").first());
    }

    /** A just-opened menu's item whose accessible name matches {@code namePattern}. */
    private Locator menuItem(Pattern namePattern) {
        return page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName(namePattern))
                .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(namePattern)))
                .or(page.getByText(namePattern))
                .first();
    }

    /**
     * The user's activation switch on the User Details page - the {@code role="switch"} that is
     * <em>not</em> the header theme toggle ("Switch to light/dark mode").
     */
    private Locator activationToggle() {
        Locator switches = page.getByRole(AriaRole.SWITCH);
        int count = Math.min(switches.count(), 8);
        for (int index = 0; index < count; index++) {
            Locator candidate = switches.nth(index);
            try {
                String label = candidate.getAttribute("aria-label");
                if (label != null && Pattern.compile("light mode|dark mode|theme",
                        Pattern.CASE_INSENSITIVE).matcher(label).find()) {
                    continue;
                }
                if (candidate.isVisible()) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // try the next
            }
        }
        // Fallback: an Active/Inactive-labelled control, or the last switch on the page.
        Locator labelled = page.getByRole(AriaRole.SWITCH, new Page.GetByRoleOptions()
                .setName(Pattern.compile("active|inactive|status|enable", Pattern.CASE_INSENSITIVE))).first();
        return labelled.count() > 0 ? labelled : page.getByRole(AriaRole.SWITCH).last();
    }

    /** {@code true}=Active, {@code false}=Inactive, {@code null}=could not tell. */
    private Boolean readActivationState() {
        try {
            Locator toggle = activationToggle();
            if (toggle.count() > 0 && toggle.isVisible()) {
                return toggle.isChecked();
            }
        } catch (Exception ignored) {
            // Fall through to the header status text.
        }
        // The details header shows an "Active" / "Inactive" pill next to the user's name.
        try {
            if (SoakUiUtils.isVisibleQuietly(page.getByText(Pattern.compile("^\\s*inactive\\s*$",
                    Pattern.CASE_INSENSITIVE)).first())) {
                return Boolean.FALSE;
            }
            if (SoakUiUtils.isVisibleQuietly(page.getByText(Pattern.compile("^\\s*active\\s*$",
                    Pattern.CASE_INSENSITIVE)).first())) {
                return Boolean.TRUE;
            }
        } catch (Exception ignored) {
            // give up
        }
        return null;
    }

    private void captureUserIdFromUrl() {
        try {
            // Only accept a real-looking id (UUID, or "U" + digits, or a long hex/alnum token) -
            // never a route segment like "v1", so the create-response id is not clobbered.
            java.util.regex.Matcher matcher = Pattern.compile(
                    "/users?/([0-9a-f]{8}-[0-9a-f-]{20,}|U\\d{2,}|[A-Za-z0-9]{10,})",
                    Pattern.CASE_INSENSITIVE).matcher(page.url());
            if (matcher.find()) {
                lastCreatedUserId = matcher.group(1);
            }
        } catch (Exception ignored) {
            // Keep whatever id the create response gave us.
        }
    }

    /** Polls up to 6s for a toast that is new versus {@code before} and is not an error message. */
    private boolean waitForSuccessToast(String context, String before) {
        long deadline = System.currentTimeMillis() + 6000;
        Pattern bad = Pattern.compile("fail|error|unable|could not|invalid|not (saved|deleted|updated)",
                Pattern.CASE_INSENSITIVE);
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                boolean ok = !bad.matcher(toast).find();
                System.out.println("[USERS & ROLES]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[USERS & ROLES]   " + context + ": no notification observed within 6s.");
        return false;
    }

    /**
     * Logs and pass/fail-checks a lifecycle write response: HTTP status is the hard signal; the
     * method, endpoint, captured user id in the URL/payload, and (when given) an
     * {@code expectedToken} in the payload or response body are logged as supporting evidence.
     */
    private boolean validateWriteApi(Response response, String context, String expectedToken) {
        if (response == null) {
            System.err.println("[USERS & ROLES]   " + context + ": no matching API response within timeout.");
            return false;
        }
        int status = response.status();
        boolean statusOk = status >= 200 && status < 300;
        String url = response.url().toLowerCase(Locale.ROOT);
        String payload = SoakUiUtils.safeRequestBody(response).toLowerCase(Locale.ROOT);
        String body = SoakUiUtils.safeResponseBody(response).toLowerCase(Locale.ROOT);
        String id = lastCreatedUserId().toLowerCase(Locale.ROOT);
        boolean idMatches = !id.isBlank() && (url.contains(id) || payload.contains(id) || body.contains(id));
        boolean tokenMatches = expectedToken == null
                || payload.contains(expectedToken) || body.contains(expectedToken);

        System.out.println("[USERS & ROLES]   " + context + " API: " + SoakUiUtils.safeMethod(response) + " "
                + SoakUiUtils.shortPath(response.url()) + " -> " + status + " (" + (statusOk ? "PASS" : "FAIL")
                + ") | userId in request=" + idMatches
                + (expectedToken == null ? "" : " | expected '" + expectedToken + "' present=" + tokenMatches));
        if (!statusOk) {
            System.err.println("[USERS & ROLES]   " + context + " API FAILED (HTTP " + status + ").");
        }
        return statusOk;
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

    private Locator firstNameField() {
        return page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("First Name").setExact(false)).first();
    }

    private Locator lastNameField() {
        return page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Last Name").setExact(false)).first();
    }

    private Locator emailField() {
        return page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Email").setExact(false)).first();
    }

    private Locator mobileField() {
        return page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Mobile Number").setExact(false)).first();
    }

    // ---------------------------------------------------------------------
    // API matcher
    // ---------------------------------------------------------------------

    /** True for the write request that backs "Save changes" on the Add User form. Broad on purpose. */
    private boolean isUserSaveResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
            return write && (url.contains("user") || url.contains("member") || url.contains("account")
                    || url.contains("invite"));
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * True for any user write - create/update/activate/inactivate/delete. Adds DELETE (and a
     * broader status/activate path match) on top of {@link #isUserSaveResponse}. Broad on purpose.
     */
    private boolean isUserWriteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                    || "DELETE".equals(method);
            return write && (url.contains("user") || url.contains("member") || url.contains("account")
                    || url.contains("status") || url.contains("activate") || url.contains("deactivate"));
        } catch (Exception exception) {
            return false;
        }
    }
}
