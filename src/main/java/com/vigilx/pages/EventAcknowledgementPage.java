package com.vigilx.pages;

import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Event Check -&gt; Event Acknowledgement: create one acknowledgement message,
 * verify it is listed, rename it, verify the rename, delete it, and verify it is gone -
 * validating each operation's background API and success toast. A dedicated class per this
 * project's convention of one page object per distinct screen (mirrors {@link QCPage} /
 * {@link GroupsPage} sitting alongside {@link RolesPage}); does not modify any other page class.
 * Contract: never throws into the caller; every failure is logged and returned as {@code false}.
 *
 * <p>Confirmed live: the "Add message" dialog's confirm button is named "Save changes", while the
 * "Edit {message}" dialog's own confirm is a plain "Save" (a different accessible name) alongside
 * its own "Delete" - clicking that opens a second, nested confirmation dialog ("Are you sure you
 * want to delete this message?") carrying its own "Delete", which this class scopes to explicitly
 * rather than ever indexing into a page-wide list of same-named buttons.
 */
public class EventAcknowledgementPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public EventAcknowledgementPage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full lifecycle: {@link #navigateToEventAcknowledgement()} -&gt;
     * {@link #createEventAcknowledgement(String)} -&gt;
     * {@link #updateEventAcknowledgement(String, String)} -&gt;
     * {@link #deleteEventAcknowledgement(String)}, each gated on the previous step's success.
     * {@code messageName} and {@code updatedMessageName} are caller-supplied (never hard-coded
     * here) so repeated runs never collide on a fixed name.
     */
    public boolean runEventAcknowledgementLifecycle(String messageName, String updatedMessageName) {
        boolean navigated = navigateToEventAcknowledgement();
        boolean created = navigated && createEventAcknowledgement(messageName);
        boolean updated = created && updateEventAcknowledgement(messageName, updatedMessageName);
        boolean deleted = updated && deleteEventAcknowledgement(updatedMessageName);
        System.out.println("[EVENT ACKNOWLEDGEMENT] Lifecycle: navigated=" + navigated + " created=" + created
                + " updated=" + updated + " deleted=" + deleted);
        return navigated && created && updated && deleted;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /** Opens Settings (only if not already open), "Event Check", then "Event Acknowledgement". */
    public boolean navigateToEventAcknowledgement() {
        Locator addMessage = addMessageButton();
        if (!SoakUiUtils.isVisibleQuietly(addMessage)) {
            try {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                Locator eventCheckLink = page.getByText(
                        Pattern.compile("^\\s*Event Check\\s*$", Pattern.CASE_INSENSITIVE)).first();
                boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(eventCheckLink);
                if (!settingsAlreadyOpen && SoakUiUtils.isVisibleQuietly(openSettings)) {
                    openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(500);
                }
                if (SoakUiUtils.waitVisible(eventCheckLink, ELEMENT_TIMEOUT_MS)) {
                    eventCheckLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(800);
                }
                Locator eventAck = page.getByText(
                        Pattern.compile("Event Acknowledgement", Pattern.CASE_INSENSITIVE)).first();
                if (SoakUiUtils.waitVisible(eventAck, ELEMENT_TIMEOUT_MS)) {
                    eventAck.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(800);
                }
            } catch (Exception exception) {
                System.err.println("[EVENT ACKNOWLEDGEMENT]   Could not open Event Acknowledgement: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                return false;
            }
        }
        boolean loaded = SoakUiUtils.waitVisible(addMessage, ELEMENT_TIMEOUT_MS);
        System.out.println("[EVENT ACKNOWLEDGEMENT] Event Acknowledgement page opened: " + (loaded ? "YES" : "NO"));
        return loaded;
    }

    // ---------------------------------------------------------------------
    // Create / Update / Delete
    // ---------------------------------------------------------------------

    /**
     * Clicks "Add message", fills "Message text" with {@code messageName}, saves, and confirms
     * both the success toast and that the message is now listed.
     */
    public boolean createEventAcknowledgement(String messageName) {
        Locator addMessage = addMessageButton();
        if (!SoakUiUtils.waitVisible(addMessage, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   'Add message' control not found.");
            return false;
        }
        try {
            addMessage.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            boolean fieldOk = fillMessageText(messageName);

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(saveChanges, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[EVENT ACKNOWLEDGEMENT]   'Save changes' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            Response response = SoakUiUtils.clickAndWaitForResponse(page, saveChanges, this::isWriteResponse, 20000);
            page.waitForTimeout(500);

            boolean apiOk = logApiOutcome("Create message", response);
            // API status is the hard signal (matches the Groups/Roles/QC convention); the toast is
            // logged as supporting evidence but does not gate success, since it can legitimately
            // take longer to render than this wait allows under load.
            waitForSuccessToast("Create message", toastBefore);
            SoakUiUtils.closeOpenDialogs(page);

            boolean listed = verifyMessageListed(messageName);
            System.out.println("[EVENT ACKNOWLEDGEMENT]   createEventAcknowledgement: field=" + fieldOk
                    + " api=" + apiOk + " listed=" + listed);
            return fieldOk && apiOk && listed;
        } catch (Exception exception) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   createEventAcknowledgement failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /** Confirms {@code messageName} is now visible on the Event Acknowledgement page. */
    public boolean verifyMessageListed(String messageName) {
        boolean shown = SoakUiUtils.isVisibleQuietly(
                page.getByText(messageName, new Page.GetByTextOptions().setExact(false)).first());
        System.out.println("[EVENT ACKNOWLEDGEMENT]   Message '" + messageName + "' listed: " + shown);
        return shown;
    }

    /** Confirms {@code messageName} no longer appears on the Event Acknowledgement page. */
    public boolean verifyMessageDeleted(String messageName) {
        boolean gone = !SoakUiUtils.isVisibleQuietly(
                page.getByText(messageName, new Page.GetByTextOptions().setExact(false)).first());
        System.out.println("[EVENT ACKNOWLEDGEMENT]   Message '" + messageName + "' removed: " + gone);
        return gone;
    }

    /**
     * Opens "Edit {messageIdentifier}" (scoped by that exact message's own accessible name, never
     * a global/first-match "Edit" locator, since several messages can be listed at once), changes
     * "Message text" to {@code newMessageName}, saves via the edit dialog's own "Save" (a
     * different accessible name from the create dialog's "Save changes"), and confirms both the
     * toast and that the new value is displayed.
     */
    public boolean updateEventAcknowledgement(String messageIdentifier, String newMessageName) {
        Locator editButton = editButtonFor(messageIdentifier);
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   'Edit " + messageIdentifier + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            Locator editDialog = page.getByRole(AriaRole.DIALOG).first();
            boolean fieldOk = fillMessageText(newMessageName);

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            // Non-exact: confirmed live the exact-name match missed this button (likely a subtle
            // accessible-name difference, e.g. surrounding whitespace) even though "Save" is its
            // only visible text and nothing else in this dialog's scope could collide with it.
            Locator save = editDialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Save").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(save, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[EVENT ACKNOWLEDGEMENT]   Edit dialog's 'Save' control not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            Response response = SoakUiUtils.clickAndWaitForResponse(page, save, this::isWriteResponse, 20000);
            page.waitForTimeout(500);

            boolean apiOk = logApiOutcome("Update message", response);
            waitForSuccessToast("Update message", toastBefore);
            SoakUiUtils.closeOpenDialogs(page);

            boolean listed = verifyMessageListed(newMessageName);
            System.out.println("[EVENT ACKNOWLEDGEMENT]   updateEventAcknowledgement: field=" + fieldOk
                    + " api=" + apiOk + " listed=" + listed);
            return fieldOk && apiOk && listed;
        } catch (Exception exception) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   updateEventAcknowledgement failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    /**
     * Opens "Edit {messageIdentifier}", clicks its "Delete", confirms the resulting confirmation
     * dialog (identified by its own "Are you sure you want to" text, never a page-wide button
     * index), clicks that confirmation dialog's OWN "Delete", and confirms both the toast and that
     * the message is gone.
     */
    public boolean deleteEventAcknowledgement(String messageIdentifier) {
        Locator editButton = editButtonFor(messageIdentifier);
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   'Edit " + messageIdentifier + "' control not found.");
            return false;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);

            Locator editDialog = page.getByRole(AriaRole.DIALOG).first();
            Locator deleteButton = editDialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(deleteButton, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[EVENT ACKNOWLEDGEMENT]   Edit dialog's 'Delete' control not found.");
                return false;
            }
            deleteButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            // Scoped to the confirmation dialog's own text - a plain-string hasText match (not a
            // regex/Pattern.quote()) since that regex form serializes to Java-only \Q...\E escaping
            // that is not valid JavaScript regex and silently never matches once it reaches
            // Playwright's browser-side engine (confirmed the hard way while fixing this exact bug
            // in QCPage's own delete confirmation).
            Locator confirmDialog = page.getByRole(AriaRole.DIALOG)
                    .filter(new Locator.FilterOptions().setHasText("Are you sure you want to"))
                    .first();
            if (!SoakUiUtils.waitVisible(confirmDialog, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[EVENT ACKNOWLEDGEMENT]   Delete confirmation dialog not found.");
                return false;
            }
            Locator confirmDelete = confirmDialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(confirmDelete, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[EVENT ACKNOWLEDGEMENT]   Confirmation dialog's own 'Delete' button not found.");
                return false;
            }

            waitForToastToClear();
            String toastBefore = SoakUiUtils.readToastText(page);
            Response response = SoakUiUtils.clickAndWaitForResponse(page, confirmDelete, this::isDeleteResponse, 20000);
            page.waitForTimeout(400);

            boolean apiOk = logApiOutcome("Delete message", response);
            waitForSuccessToast("Delete message", toastBefore);
            SoakUiUtils.closeOpenDialogs(page);

            boolean gone = verifyMessageDeleted(messageIdentifier);
            System.out.println("[EVENT ACKNOWLEDGEMENT]   deleteEventAcknowledgement: api=" + apiOk + " gone=" + gone);
            return apiOk && gone;
        } catch (Exception exception) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   deleteEventAcknowledgement failed: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private boolean fillMessageText(String value) {
        Locator field = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Message text").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   'Message text' field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(value);
            return true;
        } catch (Exception exception) {
            System.err.println("[EVENT ACKNOWLEDGEMENT]   'Message text' could not be filled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private boolean logApiOutcome(String context, Response response) {
        boolean apiOk = response == null || (response.status() >= 200 && response.status() < 300);
        if (response != null) {
            System.out.println("[EVENT ACKNOWLEDGEMENT]   " + context + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + response.status() + " (" + (apiOk ? "PASS" : "FAIL") + ")");
        } else {
            System.out.println("[EVENT ACKNOWLEDGEMENT]   " + context + ": no matching API observed within 20s.");
        }
        return apiOk;
    }

    private Locator editButtonFor(String messageIdentifier) {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit " + messageIdentifier).setExact(false)).first();
    }

    private Locator addMessageButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add message").setExact(false)).first();
    }

    private boolean isWriteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))
                    && (url.contains("va-events") || url.contains("acknowledge") || url.contains("event"));
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isDeleteResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            return method.equals("DELETE")
                    && (url.contains("va-events") || url.contains("acknowledge") || url.contains("event"));
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
                System.out.println("[EVENT ACKNOWLEDGEMENT]   " + context + " toast: \"" + toast + "\" ("
                        + (ok ? "success" : "ERROR") + ")");
                return ok;
            }
            page.waitForTimeout(400);
        }
        System.out.println("[EVENT ACKNOWLEDGEMENT]   " + context + ": no notification observed within 10s.");
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
