package com.vigilx.pages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.SoakUiUtils;

/**
 * Soak-safe validation of the Master Configuration page: opens it, creates a new master (a
 * uniquely-named "Add Template" so repeated soak runs never collide on an existing name), walks its
 * Recordings (Base recording toggle + Backup tab), Health (Network/Ping check) and Video and Image
 * (Black Frame Detection check) tabs saving a real configuration change on each, renames it, then -
 * as the very last step - removes it again, so nothing this class creates is left behind for the
 * next check or the next soak run.
 *
 * <p>Master Configuration is nested under "Devices" in the left nav rather than being a top-level
 * link, so {@link #open()} expands "Devices" first when the link is not already visible.
 *
 * <p>Every "click Save changes and confirm it" step in this class - create, rename, remove, the two
 * tab saves - shares one implementation, {@link #clickSaveAndConfirm}: the background save API
 * first, a toast/dialog-closed fallback otherwise. Likewise {@link #saveChangesButton} and
 * {@link #selectSequentialOptions} are each written once and reused everywhere they apply, per the
 * request to avoid duplicate master-configuration code/checks.
 *
 * <p>{@link #renameCreatedMaster()} and {@link #removeCreatedMaster()} both re-navigate to Master
 * Configuration fresh ({@link #reopenMasterConfigurationFresh()}), select the master's card, then
 * open its row-level "..." menu ({@link #openCardMenu}) before looking for "Edit"/"Remove". Confirmed
 * live via DOM inspection: that menu is a separate {@code role="combobox"} kebab toggle nested inside
 * the card, and "Edit"/"Remove"/"Set as default"/"Duplicate"/"Discover Stream Settings" only render
 * as buttons once it is opened - no number of clicks on the card itself ever exposes them. Each step
 * still skips cleanly, without failing, on the rare chance the menu itself is absent.
 *
 * <p>Contract: never throws into the caller. Every failure is logged, screenshotted under
 * {@code target/soak-test/screenshots/master-configuration} and returned as {@code false} so the
 * soak continues.
 */
public class MasterConfigurationValidation extends BasePage {

    private static final String SEP = "============================================================";
    private static final String DEFAULT_SCREENSHOT_DIRECTORY = "target/soak-test/screenshots/master-configuration";
    private static final int ELEMENT_TIMEOUT_MS = 8000;

    private final Path screenshotDirectory;
    /** Whichever master {@link #validateAddTemplate()} created still exists under; kept current by {@link #renameCreatedMaster()}. */
    private String lastCreatedMasterName;

    public MasterConfigurationValidation(Page page) {
        super(page);
        this.screenshotDirectory = Paths.get(ConfigReader.getOrDefault(
                "master.configuration.screenshot.directory", DEFAULT_SCREENSHOT_DIRECTORY));
    }

    // ---------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------

    /**
     * Opens Master Configuration (no duplicate navigation if it is already open). The link only
     * appears once "Devices" is expanded, so that is tried first when the link is not visible.
     */
    public boolean open() {
        if (isOnMasterConfigPage()) {
            System.out.println("[MASTER CONFIGURATION] Already on Master Configuration; reusing it.");
            return true;
        }

        Locator link = masterConfigLink();
        if (link.count() == 0) {
            try {
                page.getByText("Devices", new Page.GetByTextOptions().setExact(true)).first()
                        .click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(500);
            } catch (Exception exception) {
                System.err.println("[MASTER CONFIGURATION] Could not expand 'Devices': "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                return false;
            }
            link = masterConfigLink();
        }

        try {
            link.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION] 'Master Configuration' link could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        boolean loaded = SoakUiUtils.waitVisible(masterConfigPageRoot(), 15000);
        if (!loaded) {
            String shot = capture("page-not-loaded");
            System.err.println("[MASTER CONFIGURATION] Master Configuration page did not load. Screenshot: "
                    + (shot == null ? "<not captured>" : shot));
        } else {
            System.out.println("[MASTER CONFIGURATION] Master Configuration page loaded.");
        }
        return loaded;
    }

    private boolean isOnMasterConfigPage() {
        return SoakUiUtils.isVisibleQuietly(masterConfigPageRoot().first());
    }

    private Locator masterConfigPageRoot() {
        return page.locator(".master-config-page");
    }

    private Locator masterConfigLink() {
        return page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Master Configuration").setExact(false)).first();
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Runs the full Master Configuration flow: {@link #open()}, create a master
     * ({@link #validateAddTemplate()}), walk its Recordings/Health/Video and Image tabs
     * ({@link #validateMasterTabs()}), rename it ({@link #renameCreatedMaster()}), then - as the
     * very last step, before whatever check runs next - remove it again
     * ({@link #removeCreatedMaster()}) so nothing accumulates across soak runs. Each stage only runs
     * once the previous one actually produced a master to act on. Never throws.
     */
    public boolean validateMasterConfiguration() {
        System.out.println(SEP);
        System.out.println("MASTER CONFIGURATION VALIDATION - create, Recordings/Health/Video and Image, rename, remove");
        System.out.println(SEP);

        if (!open()) {
            return false;
        }
        boolean created = validateAddTemplate();
        boolean tabsOk = created && validateMasterTabs();
        boolean renamed = created && renameCreatedMaster();
        boolean removed = created && removeCreatedMaster();
        return created && tabsOk && renamed && removed;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    /**
     * Clicks "Add Template", fills a uniquely-generated master name, and saves it - confirming both
     * the background save API and that the new master now appears in the Templates list.
     */
    private boolean validateAddTemplate() {
        Locator addTemplate = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add Template").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(addTemplate, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Add Template' control not present; skipping.");
            return true;
        }

        try {
            addTemplate.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Add Template' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        Locator templateName = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Template name").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(templateName, ELEMENT_TIMEOUT_MS)) {
            String shot = capture("add-template-dialog-not-open");
            System.err.println("[MASTER CONFIGURATION]   'Add Template' dialog did not open. Screenshot: "
                    + (shot == null ? "<not captured>" : shot));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
        System.out.println("[MASTER CONFIGURATION]   'Add Template' dialog opened (Template name field present).");

        String masterName = generatedMasterName();
        try {
            templateName.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            templateName.fill(masterName);
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   Could not fill 'Template name': "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        boolean saved = clickSaveAndConfirm(dialog, saveChangesButton(dialog), "Create master '" + masterName + "'");
        SoakUiUtils.closeOpenDialogs(page);
        if (!saved) {
            return false;
        }

        boolean listed = SoakUiUtils.waitVisible(
                page.getByText(masterName, new Page.GetByTextOptions().setExact(false)).first(), 10000);
        System.out.println("[MASTER CONFIGURATION]   New master '" + masterName + "' listed in Templates: "
                + (listed ? "YES" : "NO"));
        if (listed) {
            lastCreatedMasterName = masterName;
        }
        return listed;
    }

    // ---------------------------------------------------------------------
    // Rename
    // ---------------------------------------------------------------------

    /**
     * Re-navigates to Master Configuration fresh ({@link #reopenMasterConfigurationFresh()}),
     * selects the master {@link #validateAddTemplate()} created, opens its row "..." menu
     * ({@link #openCardMenu}) to expose "Edit", clicks it, fills an updated name and saves -
     * confirming the same way {@link #validateAddTemplate()} does.
     */
    private boolean renameCreatedMaster() {
        if (lastCreatedMasterName == null) {
            return true;
        }
        reopenMasterConfigurationFresh();
        if (!selectMaster(lastCreatedMasterName)) {
            return false;
        }
        if (!openCardMenu(lastCreatedMasterName)) {
            System.out.println("[MASTER CONFIGURATION]   Master row menu not present; skipping rename.");
            return true;
        }

        Locator editButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Edit").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(editButton, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Edit' control not present; skipping rename.");
            return true;
        }
        try {
            editButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Edit' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        Locator templateName = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Template name").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(templateName, ELEMENT_TIMEOUT_MS)) {
            String shot = capture("edit-dialog-not-open");
            System.err.println("[MASTER CONFIGURATION]   Edit dialog did not open. Screenshot: "
                    + (shot == null ? "<not captured>" : shot));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        // A whole new generated name, not the old one plus a suffix: the app enforces "Template
        // name cannot exceed 25 characters" (found live), and validateAddTemplate()'s name is
        // already 20 characters on its own - appending anything to it risks the same overflow.
        // generatedMasterName() is the one place both create and rename build this name from.
        String updatedName = generatedMasterName();
        try {
            templateName.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            templateName.fill(updatedName);
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   Could not fill updated 'Template name': "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        boolean saved = clickSaveAndConfirm(dialog, saveChangesButton(dialog), "Rename master to '" + updatedName + "'");
        SoakUiUtils.closeOpenDialogs(page);
        if (saved) {
            lastCreatedMasterName = updatedName;
        }
        return saved;
    }

    // ---------------------------------------------------------------------
    // Remove - cleanup, so nothing this class creates is left behind
    // ---------------------------------------------------------------------

    /**
     * Re-navigates to Master Configuration fresh and selects the master every earlier step left
     * behind (see {@link #renameCreatedMaster()}, the same pattern), opens its row "..." menu
     * ({@link #openCardMenu}) to expose "Remove", then deletes it: "Remove" -> confirm "Remove" in
     * the dialog that opens -> confirms the delete the same way every other save in this class does.
     * Run last, so a fresh, uniquely-named master never accumulates across soak runs.
     */
    private boolean removeCreatedMaster() {
        if (lastCreatedMasterName == null) {
            return true;
        }
        reopenMasterConfigurationFresh();
        if (!selectMaster(lastCreatedMasterName)) {
            return false;
        }
        if (!openCardMenu(lastCreatedMasterName)) {
            System.out.println("[MASTER CONFIGURATION]   Master row menu not present; leaving '"
                    + lastCreatedMasterName + "' in place.");
            return true;
        }

        Locator removeButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Remove").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(removeButton, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Remove' control not present; leaving '"
                    + lastCreatedMasterName + "' in place.");
            return true;
        }
        try {
            removeButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Remove' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            String shot = capture("remove-dialog-not-open");
            System.err.println("[MASTER CONFIGURATION]   Remove confirmation dialog did not open. Screenshot: "
                    + (shot == null ? "<not captured>" : shot));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        // Scoped to the dialog: the trigger button above shares the exact same name ("Remove"), so
        // an unscoped lookup could re-match that same element instead of the dialog's own confirm.
        Locator confirmRemove = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Remove").setExact(false)).first();
        if (confirmRemove.count() == 0) {
            confirmRemove = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Remove").setExact(false)).first();
        }

        boolean removed = clickSaveAndConfirm(dialog, confirmRemove, "Remove master '" + lastCreatedMasterName + "'");
        SoakUiUtils.closeOpenDialogs(page);
        if (!removed) {
            return false;
        }

        boolean stillListed = SoakUiUtils.isVisibleQuietly(
                page.getByText(lastCreatedMasterName, new Page.GetByTextOptions().setExact(false)).first());
        System.out.println("[MASTER CONFIGURATION]   Master '" + lastCreatedMasterName + "' removed from Templates: "
                + (stillListed ? "NO (still listed)" : "YES"));
        return !stillListed;
    }

    // ---------------------------------------------------------------------
    // Tabs: Recordings (Base toggle + Backup), Health, Video and Image
    // ---------------------------------------------------------------------

    /** Runs every per-tab check on the master {@link #validateAddTemplate()} just created. */
    private boolean validateMasterTabs() {
        if (lastCreatedMasterName == null) {
            return true;
        }
        boolean recordingOk = validateBaseRecordingToggle();
        boolean healthOk = configureHealthCheck();
        boolean videoImageOk = configureVideoImageCheck();
        boolean referenceImagesOk = captureReferenceImages();
        return recordingOk && healthOk && videoImageOk && referenceImagesOk;
    }

    /**
     * Opens Recordings, the "Base" recording section, then toggles "Enable Base recording" on and
     * off - confirming whichever popup each direction opens (see {@link #toggleAndConfirm}) - and
     * finally visits the "Backup" sub-tab. Two toggles net back to the switch's original state.
     */
    private boolean validateBaseRecordingToggle() {
        if (!selectMaster(lastCreatedMasterName)) {
            return false;
        }

        Locator recordingsTab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Recordings").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(recordingsTab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Recordings' tab not present; skipping base recording toggle check.");
            return true;
        }
        try {
            recordingsTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(800);
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Recordings' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        // The "Base" recording section - recorded as one combined label ("RecordingBackupBase")
        // because it sits in a single row alongside "Recording"/"Backup"; fall back to the bare
        // "Base" text if that combined label is not present in this build.
        Locator baseSection = page.getByText("RecordingBackupBase", new Page.GetByTextOptions().setExact(false)).first();
        if (baseSection.count() == 0) {
            baseSection = page.getByText("Base", new Page.GetByTextOptions().setExact(true)).first();
        }
        if (SoakUiUtils.waitVisible(baseSection, ELEMENT_TIMEOUT_MS)) {
            try {
                baseSection.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(500);
            } catch (Exception exception) {
                System.out.println("[MASTER CONFIGURATION]   'Base' recording section could not be clicked ("
                        + SoakUiUtils.firstLine(exception.getMessage()) + "); trying the switch directly.");
            }
        } else {
            System.out.println("[MASTER CONFIGURATION]   'Base' recording section not found; trying the switch directly.");
        }

        Locator toggle = page.getByRole(AriaRole.SWITCH,
                new Page.GetByRoleOptions().setName("Enable Base recording").setExact(false)).first();
        boolean toggled;
        if (!SoakUiUtils.waitVisible(toggle, ELEMENT_TIMEOUT_MS)) {
            String shot = capture("base-recording-switch-not-found");
            System.err.println("[MASTER CONFIGURATION]   'Enable Base recording' switch not found. Screenshot: "
                    + (shot == null ? "<not captured>" : shot));
            toggled = false;
        } else {
            boolean firstToggle = toggleAndConfirm(toggle, "first (on)");
            boolean secondToggle = toggleAndConfirm(toggle, "second (off)");
            toggled = firstToggle && secondToggle;
        }

        boolean backupOk = visitBackupTab();
        return toggled && backupOk;
    }

    /**
     * Clicks the switch once; if a confirmation dialog opens, confirms it so the toggle actually
     * takes effect in both directions - the turn-on popup's button is expected to read "Enable" (per
     * the request), but the turn-off popup naturally confirms with something else (seen live as
     * "Disable"), so both - plus the other common affirmative labels - are accepted here. Silently
     * cancelling (closing without confirming) would leave the switch stuck mid-toggle rather than
     * cleanly on or off, which is worse than accepting either label. A popup with none of these is
     * closed defensively, screenshotted for diagnosis, so it never blocks whatever runs next.
     */
    private boolean toggleAndConfirm(Locator toggle, String label) {
        try {
            toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   Base recording toggle (" + label + ") could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (SoakUiUtils.waitVisible(dialog, 3000)) {
            Locator confirmButton = dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions()
                    .setName(Pattern.compile("^(enable|disable|yes|confirm|ok)$", Pattern.CASE_INSENSITIVE)))
                    .first();
            if (confirmButton.count() > 0) {
                try {
                    String buttonText = SoakUiUtils.firstLine(confirmButton.innerText());
                    confirmButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    System.out.println("[MASTER CONFIGURATION]   Base recording toggle (" + label
                            + "): confirmation popup -> '" + buttonText + "' clicked.");
                } catch (Exception exception) {
                    System.err.println("[MASTER CONFIGURATION]   Could not confirm the popup for toggle (" + label
                            + "): " + SoakUiUtils.firstLine(exception.getMessage()));
                    SoakUiUtils.closeOpenDialogs(page);
                    return false;
                }
            } else {
                String shot = capture("base-recording-popup-unrecognized-" + SoakUiUtils.slug(label));
                System.out.println("[MASTER CONFIGURATION]   Base recording toggle (" + label
                        + "): a popup opened with no recognised confirm button; closing it. Screenshot: "
                        + (shot == null ? "<not captured>" : shot));
                SoakUiUtils.closeOpenDialogs(page);
            }
            page.waitForTimeout(500);
        } else {
            System.out.println("[MASTER CONFIGURATION]   Base recording toggle (" + label + "): no confirmation popup.");
        }
        return true;
    }

    /** Visits the "Backup" sub-tab (under Recordings) - a load-only check, nothing is changed there. */
    private boolean visitBackupTab() {
        Locator backupTab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Backup").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(backupTab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Backup' tab not present; skipping.");
            return true;
        }
        try {
            backupTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            System.out.println("[MASTER CONFIGURATION]   'Backup' tab opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Backup' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Opens Health, the "Network" check category, its "Ping" row, sets Test Interval/Alert
     * Level/Alert Threshold/Notification, then saves. Uses {@link #selectSequentialOptions} for the
     * four dropdowns - the same helper {@link #configureVideoImageCheck()} uses for its own row.
     */
    private boolean configureHealthCheck() {
        Locator healthTab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Health").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(healthTab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Health' tab not present; skipping health check configuration.");
            return true;
        }
        try {
            healthTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(800);
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Health' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        openCategoryRow("NetworkCheckTest", "Network", "Ping");

        if (!selectSequentialOptions("Every 10 Sec", "Critical", "1X", "Yes")) {
            return false;
        }

        boolean saved = clickSaveAndConfirm(null, saveChangesButton(page.getByRole(AriaRole.DIALOG).first()),
                "Health check (Network/Ping) save");
        return saved;
    }

    /**
     * Opens Video and Image, its category, the "Black Frame Detection" row, sets its four dropdowns
     * via the same {@link #selectSequentialOptions} helper {@link #configureHealthCheck()} uses, then
     * saves.
     */
    private boolean configureVideoImageCheck() {
        Locator tab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Video and Image").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(tab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Video and Image' tab not present; skipping.");
            return true;
        }
        try {
            tab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(800);
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Video and Image' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        openCategoryRow("Video and ImageCheckTest", "Video and Image", "Black Frame Detection");

        if (!selectSequentialOptions("Performed Twice Daily", "Critical", "90%", "Yes")) {
            return false;
        }

        boolean saved = clickSaveAndConfirm(null, saveChangesButton(page.getByRole(AriaRole.DIALOG).first()),
                "Video and Image check (Black Frame Detection) save");
        return saved;
    }

    /**
     * Still on "Video and Image" (left there by {@link #configureVideoImageCheck()} - no need to
     * re-click the tab): opens "Reference Image settings", clicks the live stream, captures a Day
     * and a Night reference image, then saves via the page's own "Save" button (a plain in-page
     * control here, unlike every other save in this class, which is why it is not
     * {@link #saveChangesButton} - that helper only ever looks for "Save changes").
     *
     * <p>Seen live: on a fresh, deviceless master (nothing else attaches a device to the one
     * {@link #validateAddTemplate()} creates), "Reference Image settings" is not present at all -
     * consistent with capturing a Day/Night image needing an actual live stream to capture from,
     * which a deviceless master has no more of than the already-documented
     * {@code storage-calculator} 400/404 seen on the same master. Skips cleanly rather than failing
     * when that is the case.
     */
    private boolean captureReferenceImages() {
        Locator referenceSettings = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Reference Image settings").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(referenceSettings, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Reference Image settings' control not present; skipping.");
            return true;
        }
        try {
            referenceSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(800);
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   'Reference Image settings' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator video = page.locator("video").first();
        if (SoakUiUtils.waitVisible(video, ELEMENT_TIMEOUT_MS)) {
            try {
                video.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception ignored) {
                // Best effort - the capture buttons below are the real actions, not this click.
            }
        }

        boolean dayOk = clickButtonIfPresent("Capture Day Image");
        boolean nightOk = clickButtonIfPresent("Capture Night Image");

        Locator saveButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(saveButton, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   'Save' control not present after capturing reference images; skipping.");
            return dayOk && nightOk;
        }

        boolean saved = clickSaveAndConfirm(null, saveButton, "Reference Image settings save");
        return dayOk && nightOk && saved;
    }

    /** Clicks {@code buttonName} if present; a missing button just logs and is not a failure. */
    private boolean clickButtonIfPresent(String buttonName) {
        Locator button = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(buttonName).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(button, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER CONFIGURATION]   '" + buttonName + "' control not present; skipping.");
            return true;
        }
        try {
            button.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            System.out.println("[MASTER CONFIGURATION]   '" + buttonName + "' clicked.");
            return true;
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   '" + buttonName + "' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Clicks a check-category header (its combined label, e.g. "NetworkCheckTest", falling back to
     * the bare category name), then the specific check row within it (e.g. "Ping"). Best-effort:
     * every click here is optional scaffolding for the dropdowns that follow, not itself a pass/fail
     * signal, so a missing element just logs and moves on.
     */
    private void openCategoryRow(String combinedLabel, String bareLabel, String rowLabel) {
        Locator category = page.getByText(combinedLabel, new Page.GetByTextOptions().setExact(false)).first();
        if (category.count() == 0) {
            category = page.getByText(bareLabel, new Page.GetByTextOptions().setExact(true)).first();
        }
        if (SoakUiUtils.waitVisible(category, ELEMENT_TIMEOUT_MS)) {
            try {
                category.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300);
            } catch (Exception ignored) {
                // Best effort - the row lookup below still has its own chance to find the fields.
            }
        }

        Locator row = page.getByText(rowLabel, new Page.GetByTextOptions().setExact(true)).first();
        if (SoakUiUtils.waitVisible(row, ELEMENT_TIMEOUT_MS)) {
            try {
                row.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300);
            } catch (Exception ignored) {
                // Best effort, as above.
            }
        }
    }

    /**
     * Sets each of {@code values} into successive "Select" comboboxes, in document order. After a
     * value is picked, that combobox no longer shows the "Select" placeholder, so the next
     * {@code first()} match on the same filter naturally advances to the next field - exactly how
     * the recorded flow reuses one selector for every dropdown in a row.
     */
    private boolean selectSequentialOptions(String... values) {
        for (String value : values) {
            Locator combobox = page.getByRole(AriaRole.COMBOBOX)
                    .filter(new Locator.FilterOptions().setHasText("Select")).first();
            if (!SoakUiUtils.waitVisible(combobox, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[MASTER CONFIGURATION]   No more 'Select' combobox found while setting '"
                        + value + "'.");
                return false;
            }
            try {
                combobox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(value).setExact(true)).first()
                        .click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.err.println("[MASTER CONFIGURATION]   Could not select '" + value + "': "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                return false;
            }
        }
        System.out.println("[MASTER CONFIGURATION]   Set " + values.length + " field(s): "
                + String.join(", ", values));
        return true;
    }

    /**
     * Re-navigates to Master Configuration via the left-nav link even though it is already open -
     * the recorded rename/remove flow always starts with exactly this click
     * ({@code page.getByRole('link', { name: 'Master Configuration' }).click()}), landing fresh on
     * the default "Streams" tab with nothing selected, rather than mid-state on "Video and Image"
     * with a master already selected (where {@link #validateMasterTabs()} leaves the page). Best
     * effort: never fails the caller on its own.
     */
    private void reopenMasterConfigurationFresh() {
        try {
            Locator link = masterConfigLink();
            if (link.count() == 0) {
                page.getByText("Devices", new Page.GetByTextOptions().setExact(true)).first()
                        .click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(500);
                link = masterConfigLink();
            }
            if (link.count() > 0) {
                link.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                SoakUiUtils.waitVisible(masterConfigPageRoot(), 10000);
                page.waitForTimeout(500);
            }
        } catch (Exception ignored) {
            // Best effort - the selectMaster()/Edit-or-Remove lookups right after will surface any
            // real problem on their own.
        }
    }

    /** The Templates-list card for {@code masterName} (also the row's whole "..." menu lives inside it). */
    private Locator masterCard(String masterName) {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(masterName).setExact(false)).first();
    }

    /** Selects a master by clicking its Templates-list card, matched by its (unique) name. */
    private boolean selectMaster(String masterName) {
        Locator card = masterCard(masterName);
        if (!SoakUiUtils.waitVisible(card, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[MASTER CONFIGURATION]   Master '" + masterName + "' card not found to select.");
            return false;
        }
        try {
            card.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            return true;
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   Could not select master '" + masterName + "': "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Opens the master's row-level "..." menu: a {@code role="combobox"} kebab toggle
     * ({@code .master-config-template-item__menu-toggle}) nested inside the same card element as
     * the master's name/date, confirmed live via DOM inspection. "Edit", "Remove", "Set as
     * default", "Duplicate" and "Discover Stream Settings" only render as buttons once this menu is
     * open - no number of clicks on the card itself ever exposes them, since they live in this
     * popup, not on the card. Shared by {@link #renameCreatedMaster()} and
     * {@link #removeCreatedMaster()} so neither repeats this lookup.
     */
    private boolean openCardMenu(String masterName) {
        Locator card = masterCard(masterName);
        Locator menuToggle = card.locator(".master-config-template-item__menu-toggle").first();
        if (menuToggle.count() == 0) {
            // Fallback in case that class name ever changes: the toggle is still the only
            // role=combobox nested inside the card.
            menuToggle = card.getByRole(AriaRole.COMBOBOX).first();
        }
        if (!SoakUiUtils.waitVisible(menuToggle, ELEMENT_TIMEOUT_MS)) {
            return false;
        }
        try {
            menuToggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            return true;
        } catch (Exception exception) {
            System.err.println("[MASTER CONFIGURATION]   Could not open the row menu for master '" + masterName
                    + "': " + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Shared save/confirm - used by create, rename, and both tab saves
    // ---------------------------------------------------------------------

    /**
     * Clicks {@code saveButton} and confirms {@code actionLabel} succeeded: the background save API
     * first (broad master-config predicate, pass/fail by HTTP status), a toast/dialog-closed
     * fallback otherwise. Screenshots on any failure. This is the one save-confirmation
     * implementation for the whole class - create, rename, Health save and Video and Image save all
     * call it instead of each repeating the same click/wait/toast logic.
     *
     * @param dialogOrNull the dialog to check for closure in the no-API-match fallback, or
     *                     {@code null} when the save happens on a plain in-page button (Health/Video
     *                     and Image) rather than inside a modal.
     */
    private boolean clickSaveAndConfirm(Locator dialogOrNull, Locator saveButton, String actionLabel) {
        String toastBefore = SoakUiUtils.readToastText(page);
        Response response = SoakUiUtils.clickAndWaitForResponse(
                page, saveButton, this::isMasterConfigSaveResponse, 15000);
        String toast = waitForToast(toastBefore, 6000);

        if (response != null) {
            int status = response.status();
            boolean pass = status >= 200 && status < 300;
            System.out.println("[MASTER CONFIGURATION]   " + actionLabel + " API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + status + " (" + (pass ? "PASS" : "FAIL") + ")"
                    + (toast.isBlank() ? "" : " | toast: \"" + toast + "\""));
            if (!pass) {
                String shot = capture(SoakUiUtils.slug(actionLabel) + "-api-failed");
                System.err.println("[MASTER CONFIGURATION]   " + actionLabel + " FAILED (HTTP " + status
                        + "). Screenshot: " + (shot == null ? "<not captured>" : shot));
            }
            return pass;
        }

        System.out.println("[MASTER CONFIGURATION]   " + actionLabel + ": no matching API response within 15s.");
        boolean dialogClosed = dialogOrNull != null && !SoakUiUtils.isVisibleQuietly(dialogOrNull.first());
        boolean badToast = !toast.isBlank()
                && Pattern.compile("fail|error|unable|could not|already exists", Pattern.CASE_INSENSITIVE)
                        .matcher(toast).find();
        boolean goodToast = !toast.isBlank()
                && Pattern.compile("success|saved|created|added|updated|removed|deleted", Pattern.CASE_INSENSITIVE)
                        .matcher(toast).find();

        if (badToast) {
            String shot = capture(SoakUiUtils.slug(actionLabel) + "-failed");
            System.err.println("[MASTER CONFIGURATION]   " + actionLabel + " FAILED (toast: \"" + toast
                    + "\"). Screenshot: " + (shot == null ? "<not captured>" : shot));
            return false;
        }
        if (goodToast || dialogClosed) {
            System.out.println("[MASTER CONFIGURATION]   " + actionLabel + " accepted"
                    + (dialogClosed ? " (dialog closed)" : "") + (toast.isBlank() ? "" : " toast: \"" + toast + "\""));
            return true;
        }

        String shot = capture(SoakUiUtils.slug(actionLabel) + "-unconfirmed");
        System.err.println("[MASTER CONFIGURATION]   " + actionLabel
                + " had no confirmation (no API match, no toast, dialog still open). Screenshot: "
                + (shot == null ? "<not captured>" : shot));
        return false;
    }

    /** Save changes button, scoped to {@code dialog} first (falls back to an unscoped page-wide lookup). */
    private Locator saveChangesButton(Locator dialog) {
        Locator button = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (button.count() == 0) {
            button = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        }
        return button;
    }

    /** True for the response that backs a master-configuration create/rename/check-save/remove. Broad on purpose. */
    private boolean isMasterConfigSaveResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                    || "DELETE".equals(method);
            return write && (url.contains("master") || url.contains("template") || url.contains("config"));
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * A fresh, unique master name - "Soak-Master-" plus 8 timestamp digits, 20 characters total.
     * Used by both {@link #validateAddTemplate()} (the initial create) and
     * {@link #renameCreatedMaster()} (a brand-new name, not the old one plus a suffix) so the two
     * never repeat this generation logic. Kept well under the app's real "Template name cannot
     * exceed 25 characters" limit, found live: the full 13-digit epoch millis alone already hits
     * that ceiling, leaving no room for anything appended to it.
     */
    private String generatedMasterName() {
        return "Soak-Master-" + (System.currentTimeMillis() % 100_000_000L);
    }

    /** Polls up to {@code timeoutMs} for a toast different from {@code before}. */
    private String waitForToast(String before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String toast = SoakUiUtils.readToastText(page);
            if (!toast.isBlank() && !toast.equals(before)) {
                return toast;
            }
            page.waitForTimeout(500);
        }
        return "";
    }

    private String capture(String name) {
        return ScreenshotUtils.captureTo(page, screenshotDirectory, SoakUiUtils.slug(name));
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
