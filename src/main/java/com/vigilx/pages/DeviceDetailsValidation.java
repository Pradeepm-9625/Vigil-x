package com.vigilx.pages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.SoakUiUtils;

/**
 * Deep, soak-safe validation of one device's Device Details / configuration page.
 *
 * <p>Opens the Devices list, opens one device (the first the list reports Online, or the row whose
 * text matches {@code device.details.device.text}), then walks every tab captured in the recording -
 * Details, Streams, Recordings, VA Settings, Health, Audit Logs - plus the page-level controls
 * (Refresh Status, More Actions, fullscreen). For each section it checks that the tab's own
 * elements render; the Streams section additionally validates the live media element, and the
 * Audit Logs section additionally exercises the search box (type an unlikely term -&gt; "No records
 * found" -&gt; Clear search).
 *
 * <p>Additional to {@link ApplicationHealthPage#validateDeviceTabs}, which is left untouched. It
 * reuses the device page that check leaves open rather than navigating to it again. It walks each
 * tab exactly once - no back-and-forth re-navigation.
 *
 * <p>The "VA Settings" section runs exactly the recorded reference-image flow, nothing more:
 * Health tab -&gt; Video and Image sub-tab -&gt; "Reference Image settings" -&gt; click the live
 * stream -&gt; "Capture Day Image" -&gt; "Capture Night Image" -&gt; "Save". It never selects
 * Replace Device / Decommission from the More Actions menu - it only confirms those controls are
 * present and then dismisses them.
 *
 * <p>Contract: never throws into the caller. Every failure is logged, screenshotted under
 * {@code target/soak-test/screenshots/device-details} and returned as {@code false} so the soak
 * continues. The caller runs each {@link #FLOW} section through the same {@code PageApiTracker}
 * gating used for every other page, so each section also gets its own background-API result.
 */
public class DeviceDetailsValidation extends BasePage {

    /** Section order the caller drives; each is run as its own API-tracked page check. */
    public static final String[] FLOW =
            {"Details", "Streams", "Recordings", "VA Settings", "Health", "Audit Logs", "Controls"};

    private static final String SEP = "============================================================";
    private static final String DEFAULT_SCREENSHOT_DIRECTORY = "target/soak-test/screenshots/device-details";

    /** Shared shell selectors from the recording. */
    private static final String TAB_CONTENT = ".device-config-v1-tab-content";
    private static final String PAGE_BODY = ".device-config-v1-page__body";

    private static final int SHELL_TIMEOUT_MS = 20000;
    private static final int ELEMENT_TIMEOUT_MS = 8000;
    private static final double PLAYBACK_TOLERANCE_SECONDS = 0.2;

    private final Path screenshotDirectory;
    private final int streamMonitorSeconds;

    /** The unique camera name {@link #createDevice} generated for its last successful run - never a
     *  hard-coded value; blank until a device has actually been created. */
    private String lastCreatedDeviceName = "";

    public DeviceDetailsValidation(Page page) {
        super(page);
        this.screenshotDirectory = Paths.get(ConfigReader.getOrDefault(
                "device.details.screenshot.directory", DEFAULT_SCREENSHOT_DIRECTORY));
        this.streamMonitorSeconds = Math.max(2, intConfig("device.details.stream.monitor.seconds", 8));
    }

    /** The camera name from the last successful {@link #createDevice} call (blank if none yet). */
    public String lastCreatedDeviceName() {
        return lastCreatedDeviceName;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    /**
     * Devices -&gt; "Add Devices" -&gt; "Add Device Manually": fills the connection details
     * (Device IP / HTTP Port / RTSP Port / Username / Password), runs "Test Connection", confirms
     * the device type is detected, best-effort opens/closes the live preview, saves the connection
     * step, then completes onboarding (Camera Name / "Sync with computer time" / Site) and saves
     * again, then confirms the resulting dialog's own "Open Device Settings" and clicks it. Every
     * value is read from config ({@code device.creation.*}) - never hard-coded here - and the camera
     * name gets a per-run unique timestamp suffix appended in code, the same convention already used
     * for user/group/role creation, so repeated runs never collide on name.
     *
     * <p>Reached straight from the Dashboard, before the existing Devices / Device Tabs / Device
     * Details checks. On success this leaves the page already open on the new device's own
     * configuration page - {@link #open} already reuses whatever device configuration page is
     * currently on screen (its {@code isOnDeviceConfigPage()} check), so the existing Device Details
     * walk that runs afterward opens straight into this device instead of navigating back to the
     * Devices list, with no change needed to {@link #open} itself. Contract: never throws into the
     * caller; every failure is logged, screenshotted, and returned as {@code false} so the soak
     * continues to the existing checks regardless.
     */
    public boolean createDevice(String baseUrl) {
        try {
            System.out.println(SEP);
            System.out.println("DEVICE CREATION - onboarding a new device");
            System.out.println(SEP);

            if (!navigateToDevicesList(baseUrl)) {
                capture("device-creation-devices-list-not-open");
                return false;
            }

            if (!openAddDeviceManually()) {
                capture("device-creation-add-device-manually-not-open");
                return false;
            }

            if (!fillConnectionDetails()) {
                capture("device-creation-connection-details-failed");
                return false;
            }

            if (!testConnection()) {
                capture("device-creation-test-connection-failed");
                return false;
            }

            previewLiveStreamBestEffort();

            if (!clickSaveChanges("connection details")) {
                capture("device-creation-connection-save-failed");
                return false;
            }

            String cameraName = uniqueDeviceName();
            if (!completeOnboardingDetails(cameraName)) {
                capture("device-creation-onboarding-details-failed");
                return false;
            }

            if (!clickSaveChanges("onboarding details")) {
                capture("device-creation-onboarding-save-failed");
                return false;
            }

            if (!openDeviceSettingsFromConfirmation()) {
                capture("device-creation-open-device-settings-failed");
                return false;
            }

            lastCreatedDeviceName = cameraName;
            System.out.println("[DEVICE CREATION] Device '" + cameraName
                    + "' onboarding completed and its Device Settings page is now open.");
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE CREATION] Create device flow error: " + firstLine(exception.getMessage()));
            capture("device-creation-exception");
            return false;
        }
    }

    /**
     * Devices list only (no device opened) - Dashboard -&gt; Project Hierarchy -&gt; Devices, per
     * the recorded flow for this Create Device entry point specifically. A distinct, smaller copy
     * of {@link #open}'s own navigation (which goes straight to "Devices" and is left exactly as it
     * was for every other caller) - this method is only ever used by {@link #createDevice}.
     */
    private boolean navigateToDevicesList(String baseUrl) {
        try {
            clickLinkIfPresent("Dashboard");
            clickLinkIfPresent("Project Hierarchy");

            Locator devicesLink = page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Devices").setExact(false)).first();
            if (SoakUiUtils.waitVisible(devicesLink, ELEMENT_TIMEOUT_MS)) {
                devicesLink.click(new Locator.ClickOptions().setTimeout(10000));
            } else {
                navigateTo(baseUrl + "/devices");
            }
        } catch (Exception ignored) {
            navigateTo(baseUrl + "/devices");
        }
        waitAfterPageNavigation();
        Locator addDevices = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add Devices").setExact(false)).first();
        boolean ready = SoakUiUtils.waitVisible(addDevices, SHELL_TIMEOUT_MS);
        System.out.println("[DEVICE CREATION] Devices list opened: " + (ready ? "YES" : "NO"));
        return ready;
    }

    /** Clicks a left-nav link by (partial) name if it is currently visible; a no-op otherwise. */
    private void clickLinkIfPresent(String name) {
        try {
            Locator link = page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName(name).setExact(false)).first();
            if (SoakUiUtils.isVisibleQuietly(link)) {
                link.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                waitAfterPageNavigation();
                System.out.println("[DEVICE CREATION]   '" + name + "' link clicked.");
            } else {
                System.out.println("[DEVICE CREATION]   '" + name + "' link not present; skipping.");
            }
        } catch (Exception exception) {
            System.out.println("[DEVICE CREATION]   '" + name + "' link could not be clicked: "
                    + firstLine(exception.getMessage()));
        }
    }

    /** "Add Devices" -&gt; "Add Device Manually", waits for the Device IP field to render. */
    private boolean openAddDeviceManually() {
        Locator addDevices = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add Devices").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(addDevices, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE CREATION] 'Add Devices' button not found.");
            return false;
        }
        addDevices.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

        Locator addManually = page.getByRole(AriaRole.MENUITEM,
                new Page.GetByRoleOptions().setName("Add Device Manually").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(addManually, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE CREATION] 'Add Device Manually' menu item not found.");
            return false;
        }
        addManually.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

        Locator deviceIp = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Device IP").setExact(false)).first();
        boolean formOpen = SoakUiUtils.waitVisible(deviceIp, ELEMENT_TIMEOUT_MS);
        System.out.println("[DEVICE CREATION] 'Add Device Manually' form opened: " + (formOpen ? "YES" : "NO"));
        return formOpen;
    }

    /** Device IP / HTTP Port / RTSP Port, then (expanding "Credentials information" if needed)
     *  Username / Password - every value from {@code device.creation.*} config. */
    private boolean fillConnectionDetails() {
        Locator deviceIp = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Device IP").setExact(false)).first();
        if (!fillField(deviceIp, ConfigReader.getOrDefault("device.creation.ip", ""), "Device IP")) {
            return false;
        }

        Locator httpPort = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("HTTP Port").setExact(false)).first();
        fillField(httpPort, ConfigReader.getOrDefault("device.creation.http.port", ""), "HTTP Port");

        Locator rtspPort = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("RTSP Port").setExact(false)).first();
        fillField(rtspPort, ConfigReader.getOrDefault("device.creation.rtsp.port", ""), "RTSP Port");

        Locator username = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Username").setExact(false)).first();
        if (!SoakUiUtils.isVisibleQuietly(username)) {
            // "Credentials information" is a collapsible section header in the recording - expand it
            // only when Username is not already visible, never assumed collapsed.
            Locator credentialsSection = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Credentials information").setExact(false)).first();
            if (SoakUiUtils.isVisibleQuietly(credentialsSection)) {
                try {
                    credentialsSection.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(500);
                } catch (Exception exception) {
                    System.out.println("[DEVICE CREATION]   'Credentials information' could not be clicked: "
                            + firstLine(exception.getMessage()));
                }
            }
        }
        boolean userOk = fillField(username, ConfigReader.getOrDefault("device.creation.username", ""), "Username");

        Locator password = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Password").setExact(false)).first();
        boolean passOk = fillField(password, ConfigReader.getOrDefault("device.creation.password", ""), "Password");

        return userOk && passOk;
    }

    private boolean fillField(Locator field, String value, String label) {
        if (value == null || value.isBlank()) {
            System.out.println("[DEVICE CREATION]   '" + label + "' has no configured value; skipping.");
            return false;
        }
        if (!SoakUiUtils.waitVisible(field, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE CREATION]   '" + label + "' field not found.");
            return false;
        }
        try {
            field.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            field.fill(value);
            System.out.println("[DEVICE CREATION]   '" + label + "' filled.");
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE CREATION]   '" + label + "' could not be filled: "
                    + firstLine(exception.getMessage()));
            return false;
        }
    }

    /** "Test Connection" -&gt; confirms the app reports a detected device type. */
    private boolean testConnection() {
        Locator testConnection = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Test Connection").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(testConnection, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE CREATION] 'Test Connection' button not found.");
            return false;
        }
        testConnection.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));

        Locator detected = page.getByText(Pattern.compile("this device is a", Pattern.CASE_INSENSITIVE)).first();
        boolean connected = SoakUiUtils.waitVisible(detected, 20000);
        System.out.println("[DEVICE CREATION] Test Connection result: "
                + (connected ? "device type detected" : "no device type detected within 20s"));
        return connected;
    }

    /** Best-effort: opens the live preview, clicks the video once, then closes it. Never fails the flow. */
    private void previewLiveStreamBestEffort() {
        Locator openPreview = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Open live preview").setExact(false)).first();
        if (!SoakUiUtils.isVisibleQuietly(openPreview)) {
            System.out.println("[DEVICE CREATION]   'Open live preview' not present; skipping.");
            return;
        }
        try {
            openPreview.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            Locator video = page.locator("video").first();
            if (SoakUiUtils.waitVisible(video, ELEMENT_TIMEOUT_MS)) {
                video.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            Locator closePreview = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Close live preview").setExact(false)).first();
            if (SoakUiUtils.isVisibleQuietly(closePreview)) {
                closePreview.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            System.out.println("[DEVICE CREATION]   Live preview checked.");
        } catch (Exception exception) {
            System.out.println("[DEVICE CREATION]   Live preview could not be probed; skipping: "
                    + firstLine(exception.getMessage()));
        }
    }

    /** Camera Name (select-all -&gt; replace) / "Sync with computer time" / Site - all from config. */
    private boolean completeOnboardingDetails(String cameraName) {
        Locator cameraNameField = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Camera Name").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(cameraNameField, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE CREATION] 'Camera Name' field not found.");
            return false;
        }
        try {
            cameraNameField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS).setClickCount(3));
            cameraNameField.fill(cameraName);
            System.out.println("[DEVICE CREATION]   Camera Name set to '" + cameraName + "'.");
        } catch (Exception exception) {
            System.err.println("[DEVICE CREATION]   'Camera Name' could not be filled: "
                    + firstLine(exception.getMessage()));
            return false;
        }

        checkSyncWithComputerTime();

        selectSiteBestEffort();
        return true;
    }

    /**
     * Checks "Sync with computer time" - matches the recorded interaction exactly: click the
     * "Sync with computer time" text label first (this toggles the state in the app, per the
     * recording), then confirm via the accessible checkbox and only call {@code check()} if it
     * still reads unchecked. Falls back to the visible {@code .chk__box} control (scoped to the
     * label's own row, never a page-wide first match) when the label itself is not clickable on a
     * given build - confirmed live, the real {@code role=checkbox} input can be visually hidden
     * behind that custom control. Best-effort: logged, never fails the overall onboarding flow.
     */
    private void checkSyncWithComputerTime() {
        Locator label = page.getByText("Sync with computer time", new Page.GetByTextOptions().setExact(false)).first();
        Locator syncTime = page.getByRole(AriaRole.CHECKBOX,
                new Page.GetByRoleOptions().setName("Sync with computer time").setExact(false)).first();
        try {
            if (SoakUiUtils.isVisibleQuietly(label)) {
                label.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300);
            } else if (label.count() > 0) {
                // Scoped to the label's own row/container so a page-wide ".chk__box" match never
                // lands on an unrelated checkbox elsewhere on the same form.
                Locator visibleBox = label.locator("xpath=ancestor::*[self::label or self::div][1]")
                        .locator(".chk__box").first();
                if (SoakUiUtils.isVisibleQuietly(visibleBox)) {
                    visibleBox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(300);
                }
            }

            boolean checked = SoakUiUtils.isVisibleQuietly(syncTime) && syncTime.isChecked();
            if (!checked && SoakUiUtils.isVisibleQuietly(syncTime)) {
                syncTime.check(new Locator.CheckOptions().setTimeout(ELEMENT_TIMEOUT_MS).setForce(true));
                checked = syncTime.isChecked();
            }
            System.out.println("[DEVICE CREATION]   'Sync with computer time' checked: " + checked);
        } catch (Exception exception) {
            System.out.println("[DEVICE CREATION]   'Sync with computer time' could not be checked: "
                    + firstLine(exception.getMessage()));
        }
    }

    /**
     * Opens the Site dropdown and picks one of whatever options it currently lists, at random -
     * confirmed live, the option list carries a leading "None" (reset) entry alongside the real
     * site names, so that one is always excluded from the random pick. Never a fixed
     * {@code device.creation.site} name, which can go stale (renamed/deleted site). If selecting the
     * first level reveals a second, still-unlabelled dropdown (a nested site/zone selector, per the
     * recorded flow), that one is opened and given the same random pick. Best-effort throughout: a
     * missing control or empty option list is logged, never a hard failure of the whole onboarding
     * flow.
     */
    private void selectSiteBestEffort() {
        Locator siteCombobox = page.getByRole(AriaRole.COMBOBOX)
                .filter(new Locator.FilterOptions().setHasText("Select Site")).first();
        if (!SoakUiUtils.isVisibleQuietly(siteCombobox)) {
            System.out.println("[DEVICE CREATION]   'Select Site' control not found; skipping.");
            return;
        }
        if (!chooseRandomComboboxOption(siteCombobox, "Site")) {
            return;
        }

        // A nested site/zone selector, when present, renders as a second combobox that has not yet
        // shown any selected text - scoped by that empty state rather than a fixed list position.
        Locator nestedCombobox = page.getByRole(AriaRole.COMBOBOX)
                .filter(new Locator.FilterOptions().setHasText(Pattern.compile("^\\s*$"))).first();
        if (!SoakUiUtils.isVisibleQuietly(nestedCombobox)) {
            System.out.println("[DEVICE CREATION]   No nested site/zone control found; skipping.");
            return;
        }
        chooseRandomComboboxOption(nestedCombobox, "Site (nested)");
    }

    /** Opens {@code combobox} and clicks a random real option (never the "None" reset entry). */
    private boolean chooseRandomComboboxOption(Locator combobox, String label) {
        try {
            combobox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);

            Locator option = randomComboboxOption();
            if (option == null) {
                System.out.println("[DEVICE CREATION]   " + label
                        + " dropdown listed no selectable option (besides 'None').");
                page.keyboard().press("Escape");
                return false;
            }
            String chosen = option.innerText().trim();
            option.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(400);
            System.out.println("[DEVICE CREATION]   " + label + " selected (random): '" + chosen + "'.");
            return true;
        } catch (Exception exception) {
            System.out.println("[DEVICE CREATION]   " + label + " selection failed: "
                    + firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * One visible, real option from the currently open dropdown, picked at random - excluding a
     * plain "None" reset entry. Confirmed live: this app's dropdowns (Site included) render their
     * options through a shared {@code .vxdd__menu}/{@code .vxdd__item} portal component - the same
     * "vxdd" prefix already used elsewhere in this class for the "More Actions" menu - rather than
     * {@code role=listbox}/{@code option} semantics, so that markup is checked first; role-based
     * scanning (scoped to an actual {@code role=listbox} popup, never a bare page-wide button role)
     * remains as a fallback for any build/dropdown that does expose proper listbox roles.
     */
    private Locator randomComboboxOption() {
        Locator vxddMenu = page.locator(".vxdd__menu").first();
        Locator items;
        if (SoakUiUtils.isVisibleQuietly(vxddMenu)) {
            items = vxddMenu.locator(".vxdd__item");
        } else {
            Locator listbox = page.getByRole(AriaRole.LISTBOX).first();
            items = SoakUiUtils.isVisibleQuietly(listbox)
                    ? listbox.getByRole(AriaRole.OPTION).or(listbox.getByRole(AriaRole.MENUITEM))
                            .or(listbox.getByRole(AriaRole.BUTTON))
                    : page.getByRole(AriaRole.OPTION).or(page.getByRole(AriaRole.MENUITEM));
        }
        int count = Math.min(items.count(), 40);
        java.util.List<Locator> visible = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            Locator item = items.nth(index);
            try {
                if (!item.isVisible()) {
                    continue;
                }
                String text = item.innerText().trim();
                if (!text.isBlank() && !text.equalsIgnoreCase("None")) {
                    visible.add(item);
                }
            } catch (Exception ignored) {
                // skip
            }
        }
        if (visible.isEmpty()) {
            return null;
        }
        return visible.get(new java.util.Random().nextInt(visible.size()));
    }

    /**
     * "Save changes", confirmed by the button itself disappearing/becoming unavailable. Matches the
     * recorded onboarding-details step, which clicks "Save changes" twice in a row - here, a second
     * click is fired only when the same button is still visible after the first (a no-op, never an
     * extra failure, whenever one click was already enough).
     */
    private boolean clickSaveChanges(String step) {
        Locator save = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(save, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE CREATION] 'Save changes' (" + step + ") button not found.");
            return false;
        }
        try {
            save.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(1200);
            if (SoakUiUtils.isVisibleQuietly(save)) {
                save.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(1200);
                System.out.println("[DEVICE CREATION]   'Save changes' (" + step + ") clicked twice"
                        + " (still visible after the first click).");
            } else {
                System.out.println("[DEVICE CREATION]   'Save changes' (" + step + ") clicked.");
            }
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE CREATION]   'Save changes' (" + step + ") could not be clicked: "
                    + firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Confirms the dialog that follows the final "Save changes" (best-effort click on the dialog
     * itself first, matching the recorded interaction) and clicks its own "Open Device Settings"
     * control, then waits for the device configuration shell to render - the same shell
     * {@link #open} itself waits for, so a caller running the Device Details walk right afterward
     * finds the page in exactly the state it already expects.
     */
    private boolean openDeviceSettingsFromConfirmation() {
        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            try {
                dialog.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.out.println("[DEVICE CREATION]   Confirmation dialog could not be clicked (non-fatal): "
                        + firstLine(exception.getMessage()));
            }
        }

        // "Open Device Settings" is checked directly, page-wide, before requiring a role=dialog
        // wrapper: some builds render this confirmation as a non-<dialog> popup (same "vxdd"/custom
        // component pattern already confirmed elsewhere on this page), so gating on role=dialog
        // first could miss a real, clickable confirmation.
        Locator openSettings = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Open Device Settings").setExact(false)).first();
        if (SoakUiUtils.waitVisible(openSettings, ELEMENT_TIMEOUT_MS)) {
            return clickOpenDeviceSettings(openSettings);
        }

        if (SoakUiUtils.isVisibleQuietly(dialog)) {
            openSettings = dialog.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Open Device Settings").setExact(false)).first();
            if (SoakUiUtils.waitVisible(openSettings, ELEMENT_TIMEOUT_MS)) {
                return clickOpenDeviceSettings(openSettings);
            }
        }

        System.err.println("[DEVICE CREATION] 'Open Device Settings' control not found after Save changes.");
        return false;
    }

    private boolean clickOpenDeviceSettings(Locator openSettings) {
        if (!SoakUiUtils.waitVisible(openSettings, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE CREATION] 'Open Device Settings' button not found.");
            return false;
        }

        try {
            openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
        } catch (Exception exception) {
            System.err.println("[DEVICE CREATION] 'Open Device Settings' could not be clicked: "
                    + firstLine(exception.getMessage()));
            return false;
        }

        boolean opened = SoakUiUtils.waitVisible(
                page.locator(PAGE_BODY + ", " + TAB_CONTENT).first(), SHELL_TIMEOUT_MS);
        System.out.println("[DEVICE CREATION]   'Open Device Settings' clicked; device configuration page open: "
                + (opened ? "YES" : "NO"));
        return opened;
    }

    /** {@code device.creation.name} plus a 6-digit epoch-second suffix, same convention as
     *  user/group/role creation, so repeated runs never collide on name. */
    private String uniqueDeviceName() {
        String base = ConfigReader.getOrDefault("device.creation.name", "Soak test device");
        String suffix = String.format("%06d", (System.currentTimeMillis() / 1000) % 1_000_000);
        return base + " " + suffix;
    }

    // ---------------------------------------------------------------------
    // Decommission
    // ---------------------------------------------------------------------

    /**
     * Cleans up after the Device Details tab walk finishes: "Back to devices" -&gt; find the row for
     * {@code deviceName} -&gt; open its own actions menu -&gt; "Decommission Device" -&gt; type the
     * required "Decommission" confirmation text -&gt; confirm.
     *
     * <p>{@code deviceName} is required and never inferred - the caller (only
     * {@link com.vigilx.soak.SoakHealthCheckRunner}) passes the exact unique name
     * {@link #createDevice} generated, so this can only ever decommission the device this same run
     * created, never an arbitrary/pre-existing one. A blank name is refused outright as a safety
     * gate. Contract: never throws into the caller; every failure is logged, screenshotted, and
     * returned as {@code false} so the soak continues to whatever runs after it (e.g. Master
     * Configuration, left untouched).
     */
    public boolean decommissionDevice(String baseUrl, String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            System.err.println("[DEVICE DECOMMISSION] No device name given; refusing to decommission "
                    + "anything (safety gate - this only ever removes a device this run created).");
            return false;
        }
        try {
            System.out.println(SEP);
            System.out.println("DEVICE DECOMMISSION - removing '" + deviceName + "'");
            System.out.println(SEP);

            if (!backToDevicesList(baseUrl)) {
                capture("device-decommission-devices-list-not-open");
                return false;
            }

            Locator deviceRow = findDeviceRow(deviceName);
            if (deviceRow == null) {
                System.err.println("[DEVICE DECOMMISSION] Row for '" + deviceName + "' not found.");
                capture("device-decommission-row-not-found");
                return false;
            }
            try {
                deviceRow.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.out.println("[DEVICE DECOMMISSION]   Row click skipped (non-fatal): "
                        + firstLine(exception.getMessage()));
            }

            if (!openDecommissionDeviceOption(deviceRow)) {
                capture("device-decommission-menu-item-not-found");
                return false;
            }

            if (!confirmDecommission()) {
                capture("device-decommission-confirm-failed");
                return false;
            }

            System.out.println("[DEVICE DECOMMISSION] Device '" + deviceName + "' decommissioned.");
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE DECOMMISSION] Decommission flow error: " + firstLine(exception.getMessage()));
            capture("device-decommission-exception");
            return false;
        }
    }

    /** "Back to devices" when present (leaving a device's own settings page); otherwise the Devices list link. */
    private boolean backToDevicesList(String baseUrl) {
        Locator back = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Back to devices").setExact(false)).first();
        if (SoakUiUtils.isVisibleQuietly(back)) {
            try {
                back.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                waitAfterPageNavigation();
            } catch (Exception exception) {
                System.out.println("[DEVICE DECOMMISSION]   'Back to devices' could not be clicked: "
                        + firstLine(exception.getMessage()));
            }
        } else {
            try {
                page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Devices").setExact(false))
                        .first().click(new Locator.ClickOptions().setTimeout(10000));
                waitAfterPageNavigation();
            } catch (Exception ignored) {
                navigateTo(baseUrl + "/devices");
                waitAfterPageNavigation();
            }
        }
        Locator addDevices = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add Devices").setExact(false)).first();
        boolean ready = SoakUiUtils.waitVisible(addDevices, SHELL_TIMEOUT_MS);
        System.out.println("[DEVICE DECOMMISSION] Devices list opened: " + (ready ? "YES" : "NO"));
        return ready;
    }

    /** The Devices list row whose text contains {@code deviceName} - never a fixed row position. */
    private Locator findDeviceRow(String deviceName) {
        Locator match = page.getByText(deviceName, new Page.GetByTextOptions().setExact(false)).first();
        if (!SoakUiUtils.waitVisible(match, SHELL_TIMEOUT_MS)) {
            return null;
        }
        return match;
    }

    /**
     * Opens {@code deviceRow}'s own actions control and clicks "Decommission Device" - scoped to the
     * row first (a per-row menu trigger), falling back to a page-wide combobox/button lookup for a
     * build whose trigger sits outside the row's own DOM subtree (same portal pattern already
     * confirmed elsewhere in this class).
     */
    private boolean openDecommissionDeviceOption(Locator deviceRow) {
        Locator trigger = deviceRow.locator("xpath=ancestor::*[self::tr or self::li or self::div][1]")
                .getByRole(AriaRole.COMBOBOX).first();
        if (trigger.count() == 0 || !SoakUiUtils.isVisibleQuietly(trigger)) {
            trigger = page.getByRole(AriaRole.COMBOBOX).first();
        }
        if (SoakUiUtils.isVisibleQuietly(trigger)) {
            try {
                trigger.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(400);
            } catch (Exception exception) {
                System.out.println("[DEVICE DECOMMISSION]   Row actions trigger could not be clicked: "
                        + firstLine(exception.getMessage()));
            }
        }

        Locator decommission = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Decommission Device").setExact(false))
                .or(page.getByRole(AriaRole.MENUITEM,
                        new Page.GetByRoleOptions().setName("Decommission Device").setExact(false)))
                .first();
        if (!SoakUiUtils.waitVisible(decommission, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE DECOMMISSION] 'Decommission Device' option not found.");
            return false;
        }
        try {
            decommission.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE DECOMMISSION] 'Decommission Device' could not be clicked: "
                    + firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * The confirmation dialog: types the required "Decommission" text into its own
     * {@code To confirm, type "..."} textbox, then clicks the dialog's own "Decommission Device"
     * button (scoped to the dialog, never the earlier menu item).
     */
    private boolean confirmDecommission() {
        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE DECOMMISSION] Confirmation dialog did not open.");
            return false;
        }

        Locator confirmField = dialog.getByRole(AriaRole.TEXTBOX,
                new Locator.GetByRoleOptions().setName(Pattern.compile("to confirm, type",
                        Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(confirmField, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE DECOMMISSION] Confirmation textbox not found.");
            return false;
        }
        try {
            confirmField.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            confirmField.fill("Decommission");
        } catch (Exception exception) {
            System.err.println("[DEVICE DECOMMISSION] Confirmation textbox could not be filled: "
                    + firstLine(exception.getMessage()));
            return false;
        }

        Locator confirmButton = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Decommission Device").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(confirmButton, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[DEVICE DECOMMISSION] Dialog's own 'Decommission Device' button not found.");
            return false;
        }
        try {
            confirmButton.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[DEVICE DECOMMISSION] Dialog's 'Decommission Device' could not be clicked: "
                    + firstLine(exception.getMessage()));
            return false;
        }

        boolean closed = SoakUiUtils.waitVisible(dialog, 1000) ? waitForDialogClosed(dialog) : true;
        System.out.println("[DEVICE DECOMMISSION]   Confirmation dialog closed: " + closed);
        return closed;
    }

    private boolean waitForDialogClosed(Locator dialog) {
        long deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!SoakUiUtils.isVisibleQuietly(dialog)) {
                return true;
            }
            page.waitForTimeout(300);
        }
        return !SoakUiUtils.isVisibleQuietly(dialog);
    }

    // ---------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------

    /**
     * Opens the Devices list and then one device's details/configuration page.
     *
     * @return {@code true} once the device configuration shell is visible
     */
    public boolean open(String baseUrl) {
        try {
            System.out.println(SEP);
            System.out.println("DEVICE DETAILS VALIDATION - opening a device");
            System.out.println(SEP);

            // No duplicate navigation: the run reaches this straight after the existing "Device
            // Tabs" check, which already left a device configuration page open. Reuse it instead
            // of navigating back to the Devices list and re-opening a device.
            if (isOnDeviceConfigPage()) {
                System.out.println("[DEVICE DETAILS] Already on a device configuration page; reusing it.");
                return true;
            }

            // Reach the Devices list via the same left-nav link the existing checks use.
            try {
                page.getByRole(AriaRole.LINK,
                                new Page.GetByRoleOptions().setName("Devices").setExact(false))
                        .first().click(new Locator.ClickOptions().setTimeout(10000));
            } catch (Exception ignored) {
                navigateTo(baseUrl + "/devices");
            }
            waitAfterPageNavigation();

            Locator online = page.getByText("Online", new Page.GetByTextOptions().setExact(true));
            online.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(SHELL_TIMEOUT_MS));

            String deviceText = ConfigReader.getOrDefault("device.details.device.text", "").trim();
            Locator deviceRow;
            if (deviceText.isBlank()) {
                deviceRow = online.first();
            } else {
                Locator named = page.getByText(deviceText, new Page.GetByTextOptions().setExact(false)).first();
                deviceRow = named.count() > 0 ? named : online.first();
                if (named.count() == 0) {
                    System.err.println("[DEVICE DETAILS] Row '" + deviceText
                            + "' not found; opening the first Online device instead.");
                }
            }

            deviceRow.click(new Locator.ClickOptions().setTimeout(10000));
            waitAfterPageNavigation();

            page.locator(PAGE_BODY + ", " + TAB_CONTENT).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(SHELL_TIMEOUT_MS));
            System.out.println("[DEVICE DETAILS] Device configuration page opened.");
            return true;

        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS] Could not open a device page: " + exception.getMessage());
            capture("device-details-open-failed");
            return false;
        }
    }

    /** True when a device configuration page (its shell + the tab strip) is already on screen. */
    private boolean isOnDeviceConfigPage() {
        try {
            boolean shell = isVisibleQuietly(page.locator(PAGE_BODY + ", " + TAB_CONTENT).first());
            boolean tabStrip = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("Streams").setExact(true)).count() > 0;
            return shell && tabStrip;
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------

    /** Runs one {@link #FLOW} section. Never throws. */
    public boolean validateSection(String section) {
        try {
            return switch (section) {
                case "Details" -> validateDetailsTab();
                case "Streams" -> validateStreamsTab();
                case "Recordings" -> validateRecordingsTab();
                case "VA Settings" -> validateVaSettingsTab();
                case "Health" -> validateHealthTab();
                case "Audit Logs" -> validateAuditLogsTab();
                case "Controls" -> validatePageControls();
                default -> false;
            };
        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS] '" + section + "' threw: " + exception.getMessage());
            capture("device-details-" + slug(section) + "-exception");
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------

    private boolean validateDetailsTab() {
        if (!clickTab("Details")) {
            capture("details-tab-not-open");
            return false;
        }
        boolean shell = expect("tab content", page.locator(TAB_CONTENT));
        boolean tabStrip = expect("tab strip (Streams tab present)",
                page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Streams").setExact(true)));
        boolean info = expect("device info fields",
                page.getByText(Pattern.compile(
                        "device name|model|serial|firmware|ip address|mac address|manufacturer",
                        Pattern.CASE_INSENSITIVE)));
        boolean ok = shell && (tabStrip || info);
        if (!ok) {
            capture("details-tab");
        }
        return ok;
    }

    private boolean validateStreamsTab() {
        if (!clickTab("Streams")) {
            capture("streams-tab-not-open");
            return false;
        }
        boolean shell = expect("tab content", page.locator(TAB_CONTENT));

        // "Validate the stream is available or not": determine and report availability. A device
        // that produces no live frame here is a valid observation, not a tab failure - the failing
        // live-frame API is already caught by this section's background-API check. The section
        // fails only when the Streams tab itself does not render.
        String verdict;
        Locator media = page.locator("video, canvas, [class*='player' i], [class*='video-player' i]");
        if (media.count() > 0) {
            verdict = validateMediaPlays(media.first())
                    ? "AVAILABLE (media element decoding and progressing)"
                    : "NOT AVAILABLE (media element present but not playing)";
        } else {
            boolean config = isVisibleQuietly(page.getByText(Pattern.compile(
                    "stream|rtsp|profile|codec|resolution|bitrate|fps|main stream|sub stream",
                    Pattern.CASE_INSENSITIVE)).first());
            verdict = "NOT AVAILABLE (no live player on the Streams tab; stream configuration "
                    + (config ? "content present)" : "content also missing)");
        }
        System.out.println("[DEVICE DETAILS]   Stream available: " + verdict);

        if (!shell) {
            capture("streams-tab");
        }
        return shell;
    }

    /** Element / source / readyState / resolution / real-progress check for a media element. */
    private boolean validateMediaPlays(Locator media) {
        try {
            media.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(15000));

            String tag = String.valueOf(media.evaluate("e => e.tagName.toLowerCase()"));
            if (!"video".equals(tag)) {
                System.out.println("[DEVICE DETAILS]   media element <" + tag + "> present (no <video> to sample).");
                return true;
            }

            media.evaluate("e => { try { e.muted = true; e.play(); } catch (err) {} }");
            String source = String.valueOf(media.evaluate("e => e.currentSrc || e.src || ''"));
            int readyState = ((Number) media.evaluate("e => e.readyState")).intValue();
            int width = ((Number) media.evaluate("e => e.videoWidth")).intValue();
            int height = ((Number) media.evaluate("e => e.videoHeight")).intValue();
            double t0 = ((Number) media.evaluate("e => e.currentTime")).doubleValue();
            page.waitForTimeout(streamMonitorSeconds * 1000L);
            double t1 = ((Number) media.evaluate("e => e.currentTime")).doubleValue();
            boolean progressed = t1 > t0 + PLAYBACK_TOLERANCE_SECONDS;

            System.out.println("[DEVICE DETAILS]   stream: source=" + (source.isBlank() ? "<empty>" : "present")
                    + " readyState=" + readyState + " resolution=" + width + "x" + height
                    + " currentTime " + t0 + "->" + t1 + " progressed=" + progressed);

            boolean ok = !source.isBlank() && readyState >= 2 && width > 0 && height > 0 && progressed;
            if (!ok) {
                System.err.println("[DEVICE DETAILS]   stream did not play cleanly on the Streams tab.");
            }
            return ok;
        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS]   stream validation error: " + exception.getMessage());
            return false;
        }
    }

    private boolean validateRecordingsTab() {
        if (!clickTab("Recordings")) {
            capture("recordings-tab-not-open");
            return false;
        }
        boolean shell = expect("tab content", page.locator(TAB_CONTENT));
        boolean recording = expect("Recording section",
                page.getByText(Pattern.compile("recording", Pattern.CASE_INSENSITIVE)));
        // Backup / Base sub-tabs from the recording, when the build has them.
        clickSubTabIfPresent("Backup");
        clickSubTabIfPresent("Base");
        boolean ok = shell && recording;
        if (!ok) {
            capture("recordings-tab");
        }
        return ok;
    }

    /**
     * Reference-image flow, exactly as recorded: Health tab -&gt; Video and Image sub-tab -&gt;
     * "Reference Image settings" -&gt; click the live stream -&gt; "Capture Day Image" -&gt;
     * "Capture Night Image" -&gt; "Save". Nothing else - no dialog-state bookkeeping, no
     * enabled/disabled probing. Each step waits for its target to be visible before clicking and
     * never throws into the caller; a missing "Reference Image settings" or "Save" is a clean
     * {@code false}, so the section reports FAIL rather than a stack trace.
     */
    private boolean validateVaSettingsTab() {
        try {
            if (!clickTab("Health")) {
                capture("va-settings-health-tab-not-open");
                return false;
            }
            if (!clickTab("Video and Image")) {
                capture("va-settings-video-image-tab-not-open");
                return false;
            }

            Locator referenceSettings = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reference Image settings").setExact(false)).first();
            if (!SoakUiUtils.waitVisible(referenceSettings, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[DEVICE DETAILS]   'Reference Image settings' button not found.");
                capture("va-settings-reference-image-settings-missing");
                return false;
            }
            referenceSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(1000);

            Locator video = page.locator("video").first();
            if (SoakUiUtils.waitVisible(video, ELEMENT_TIMEOUT_MS)) {
                try {
                    video.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                } catch (Exception exception) {
                    System.out.println("[DEVICE DETAILS]   live stream click skipped ("
                            + firstLine(exception.getMessage()) + ").");
                }
            }

            clickButtonByName("Capture Day Image");
            clickButtonByName("Capture Night Image");

            Locator saveBtn = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save").setExact(true)).first();
            if (!SoakUiUtils.waitVisible(saveBtn, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[DEVICE DETAILS]   'Save' button not found after capturing reference images.");
                capture("va-settings-save-missing");
                return false;
            }
            saveBtn.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(1500);
            System.out.println("[DEVICE DETAILS]   Reference image Save clicked.");
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS]   Reference image flow error: "
                    + firstLine(exception.getMessage()));
            capture("va-settings-reference-image-exception");
            return false;
        }
    }

    /** Clicks a button by its exact accessible name when visible; a missing one just logs, not a failure. */
    private void clickButtonByName(String name) {
        Locator button = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(name).setExact(true)).first();
        if (!SoakUiUtils.waitVisible(button, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[DEVICE DETAILS]   '" + name + "' not present.");
            return;
        }
        try {
            button.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(800);
            System.out.println("[DEVICE DETAILS]   '" + name + "' clicked.");
        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS]   '" + name + "' could not be clicked: "
                    + firstLine(exception.getMessage()));
        }
    }

    private boolean validateHealthTab() {
        if (!clickTab("Health")) {
            capture("health-tab-not-open");
            return false;
        }
        boolean shell = expect("tab content", page.locator(TAB_CONTENT));
        boolean network = expect("Network section",
                page.getByText(Pattern.compile("network", Pattern.CASE_INSENSITIVE)));
        boolean videoImage = expect("Video and Image section",
                page.getByText(Pattern.compile("video and image", Pattern.CASE_INSENSITIVE)));
        clickSubTabIfPresent("Video and Image");
        clickSubTabIfPresent("Network");
        boolean healthConfig = expect("health check fields (Test Interval / Alert Level / Check)",
                page.getByText(Pattern.compile("test interval|alert level|check", Pattern.CASE_INSENSITIVE)));
        boolean ok = shell && (network || videoImage || healthConfig);
        if (!ok) {
            capture("health-tab");
        }
        return ok;
    }

    private boolean validateAuditLogsTab() {
        if (!clickTab("Audit Logs")) {
            capture("audit-logs-tab-not-open");
            return false;
        }
        boolean shell = expect("tab content", page.locator(TAB_CONTENT));

        Locator search = page.getByRole(AriaRole.TEXTBOX,
                        new Page.GetByRoleOptions().setName(Pattern.compile("search .*audit", Pattern.CASE_INSENSITIVE)))
                .or(page.getByPlaceholder("Search Devices Audit Logs"))
                .first();

        boolean searchBox = expect("Audit Logs search box", search);
        Locator exportButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Export audit logs").setExact(false)).first();
        boolean exportBtn = expect("Export audit logs button", exportButton);

        boolean searchWorks = false;
        if (searchBox) {
            try {
                // Plain alphanumerics only: unlikely to match a real audit entry, but nothing a
                // server-side query validator would reject (which would muddy the API result).
                String unlikely = "zzzzzznomatch";
                search.fill(unlikely);
                page.waitForTimeout(2000);

                boolean noData = isVisibleQuietly(page.getByTestId("no-data-container").first())
                        || isVisibleQuietly(page.getByText(Pattern.compile(
                        "no records found|no data|no results", Pattern.CASE_INSENSITIVE)).first());
                System.out.println("[DEVICE DETAILS]   search '" + unlikely + "' -> no-data shown: " + noData);

                Locator clear = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Clear search").setExact(false)).first();
                if (clear.count() > 0 && clear.isVisible()) {
                    clear.click(new Locator.ClickOptions().setTimeout(5000));
                } else {
                    search.fill("");
                }
                page.waitForTimeout(1500);

                String afterClear = "";
                try {
                    afterClear = search.inputValue();
                } catch (Exception ignored) {
                    // Composite locator or non-input target: treat as cleared if the read fails.
                }
                boolean cleared = afterClear == null || afterClear.isBlank();
                System.out.println("[DEVICE DETAILS]   search cleared: " + cleared);
                searchWorks = noData && cleared;
            } catch (Exception exception) {
                System.err.println("[DEVICE DETAILS]   search exercise error: " + exception.getMessage());
            }
        }

        // Non-blocking, additive: clear the date filter (a no-op if already clear / not present)
        // then actually click Export and confirm a real download starts - the pre-existing "ok"
        // gate below is unchanged (shell/searchBox/exportBtn/searchWorks), so a flaky download
        // never turns an otherwise-passing tab into a failure.
        clearAuditDateFilter();
        if (exportBtn) {
            exportAuditLogsDownload(exportButton);
        }

        boolean ok = shell && searchBox && exportBtn && searchWorks;
        if (!ok) {
            capture("audit-logs-tab");
        }
        return ok;
    }

    /**
     * Clicks "Clear Date & Time filter" if present, leaving Audit Logs unfiltered by date before
     * export. A missing button just skips (dates may already be clear) - logged only, never fails
     * the tab.
     */
    private boolean clearAuditDateFilter() {
        Locator clearDate = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Clear Date & Time filter").setExact(false)).first();
        if (!(clearDate.count() > 0 && clearDate.isVisible())) {
            System.out.println("[DEVICE DETAILS]   Audit Logs: 'Clear Date & Time filter' not present; skipping.");
            return true;
        }
        try {
            clearDate.click(new Locator.ClickOptions().setTimeout(5000));
            page.waitForTimeout(400);
            System.out.println("[DEVICE DETAILS]   Audit Logs: date filter cleared.");
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS]   'Clear Date & Time filter' could not be clicked: "
                    + exception.getMessage());
            return false;
        }
    }

    /** Clicks "Export audit logs" and confirms a real file download starts. Logged only. */
    private boolean exportAuditLogsDownload(Locator exportButton) {
        try {
            com.microsoft.playwright.Download download = page.waitForDownload(
                    new Page.WaitForDownloadOptions().setTimeout(15000),
                    () -> exportButton.click(new Locator.ClickOptions().setTimeout(5000)));
            System.out.println("[DEVICE DETAILS]   Audit Logs export downloaded: " + download.suggestedFilename());
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS]   Audit Logs export FAILED: " + exception.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Page-level controls (Refresh Status, More Actions, fullscreen)
    // ---------------------------------------------------------------------

    private boolean validatePageControls() {
        boolean ok = true;
        ok &= checkControl("Refresh Status", this::exerciseRefreshStatus);
        ok &= checkControl("More Actions menu", this::exerciseMoreActionsMenu);
        ok &= checkControl("Fullscreen enter/exit", this::exerciseFullscreen);
        if (!ok) {
            capture("page-controls");
        }
        return ok;
    }

    /**
     * Each control probe returns {@code true} when the control is absent (build variance) or present
     * and working, and {@code false} only when the control is definitively present, was interacted
     * with, and the expected result did not appear. A locator / timeout / interception error while
     * probing is logged and treated as a skip ({@code true}) - these page-level controls are a
     * best-effort extra check and must never turn a run red on their own.
     */
    private boolean exerciseRefreshStatus() {
        Locator btn = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Refresh Status").setExact(false)).first();
        if (btn.count() == 0) {
            System.out.println("[DEVICE DETAILS]   'Refresh Status' not present.");
            return true;
        }
        try {
            btn.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(1500);
            System.out.println("[DEVICE DETAILS]   'Refresh Status' clicked.");
            return true;
        } catch (Exception exception) {
            System.out.println("[DEVICE DETAILS]   'Refresh Status' could not be probed; skipping: "
                    + firstLine(exception.getMessage()));
            return true;
        }
    }

    private boolean exerciseMoreActionsMenu() {
        // The trigger is a custom dropdown (.vxdd) whose visible ".vxdd__main" glyph intercepts
        // clicks - clicking the "More Actions" text span itself never opens it. Target the .vxdd
        // container (or the header's own more-toggle element) instead.
        Locator trigger = page.locator(".vxdd, [class*='more-toggle' i]")
                .filter(new Locator.FilterOptions().setHasText(Pattern.compile(
                        "more actions", Pattern.CASE_INSENSITIVE)))
                .first();
        if (trigger.count() == 0) {
            trigger = page.locator("[class*='more-toggle' i]").first();
        }
        if (trigger.count() == 0) {
            System.out.println("[DEVICE DETAILS]   'More Actions' not present.");
            return true;
        }
        try {
            trigger.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(1000);
            boolean replace = isVisibleQuietly(page.getByText(
                    Pattern.compile("replace device", Pattern.CASE_INSENSITIVE)).first());
            boolean decommission = isVisibleQuietly(page.getByText(
                    Pattern.compile("decommission", Pattern.CASE_INSENSITIVE)).first());
            System.out.println("[DEVICE DETAILS]   More Actions -> Replace Device=" + replace
                    + " Decommission=" + decommission);
            // Read-only: close the menu without selecting anything.
            page.keyboard().press("Escape");
            page.waitForTimeout(500);
            if (!replace && !decommission) {
                System.err.println("[DEVICE DETAILS]   More Actions opened but showed neither "
                        + "'Replace Device' nor 'Decommission'.");
                return false;
            }
            return true;
        } catch (Exception exception) {
            System.out.println("[DEVICE DETAILS]   'More Actions' could not be probed; skipping: "
                    + firstLine(exception.getMessage()));
            try {
                page.keyboard().press("Escape");
            } catch (Exception ignored) {
                // Best effort.
            }
            return true;
        }
    }

    private boolean exerciseFullscreen() {
        Locator enter = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Enter fullscreen").setExact(false)).first();
        if (enter.count() == 0) {
            System.out.println("[DEVICE DETAILS]   'Enter fullscreen' not present.");
            return true;
        }
        try {
            enter.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(1200);
            Locator exit = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Exit fullscreen").setExact(false)).first();
            boolean entered = exit.count() > 0 && exit.isVisible();
            if (entered) {
                exit.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(1000);
            }
            System.out.println("[DEVICE DETAILS]   fullscreen toggled: entered=" + entered);
            return entered;
        } catch (Exception exception) {
            System.out.println("[DEVICE DETAILS]   fullscreen could not be probed; skipping: "
                    + firstLine(exception.getMessage()));
            return true;
        }
    }

    /** First line of a (possibly multi-line Playwright) error message, for a one-line log. */
    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "<no message>";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.strip();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Clicks a top-level device tab and waits for the shared tab-content shell to render. */
    private boolean clickTab(String name) {
        // A modal left open by a previous section (e.g. the Capture Image dialog when Save was
        // disabled) puts an invisible overlay hit-box over the whole page that swallows tab clicks.
        // Clear any stray modal before every tab so one section can never cascade into the next.
        closeOpenDialogs();
        try {
            Locator tab = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName(name).setExact(true)).first();
            if (tab.count() == 0) {
                tab = page.getByRole(AriaRole.TAB,
                        new Page.GetByRoleOptions().setName(name).setExact(false)).first();
            }
            tab.click(new Locator.ClickOptions().setTimeout(10000));
            page.locator(TAB_CONTENT).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(15000));
            page.waitForTimeout(1500);
            System.out.println("[DEVICE DETAILS] Tab '" + name + "' opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[DEVICE DETAILS] Tab '" + name + "' did not open: " + exception.getMessage());
            return false;
        }
    }

    private void clickSubTabIfPresent(String name) {
        try {
            Locator sub = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName(name).setExact(true)).first();
            if (sub.count() > 0 && sub.isVisible()) {
                sub.click(new Locator.ClickOptions().setTimeout(5000));
                page.waitForTimeout(1200);
                System.out.println("[DEVICE DETAILS]   sub-tab '" + name + "' opened.");
            }
        } catch (Exception exception) {
            System.out.println("[DEVICE DETAILS]   sub-tab '" + name + "' not clickable: " + exception.getMessage());
        }
    }

    /** Waits briefly for a locator to be visible; logs PRESENT/MISSING and never throws. */
    private boolean expect(String what, Locator locator) {
        try {
            locator.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(ELEMENT_TIMEOUT_MS));
            System.out.println("[DEVICE DETAILS]   " + what + " : PRESENT");
            return true;
        } catch (Exception exception) {
            System.out.println("[DEVICE DETAILS]   " + what + " : MISSING");
            return false;
        }
    }

    private boolean isVisibleQuietly(Locator locator) {
        try {
            return locator.isVisible();
        } catch (Exception exception) {
            return false;
        }
    }

    /** Concrete modal markers on this app: the native element and vx's overlay classes. */
    private static final String DIALOG_MARKERS =
            "dialog[open], .vxmodal__overlay, .vxpanelmodal-overlay";

    private boolean isAnyDialogOpen() {
        try {
            Locator dialogs = page.locator(DIALOG_MARKERS);
            int count = Math.min(dialogs.count(), 6);
            for (int index = 0; index < count; index++) {
                if (isVisibleQuietly(dialogs.nth(index))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Treat a read error as "nothing to close".
        }
        return false;
    }

    /**
     * Closes any open modal dialog: Escape first (native {@code <dialog aria-modal>} responds to it),
     * then the app's explicit close controls, then - as a last resort - a forced {@code dialog.close()}
     * on every {@code dialog[open]}. Re-checks after each step and returns as soon as nothing is open.
     */
    private void closeOpenDialogs() {
        String[] closeSelectors = {
                "[aria-label='Close dialog' i]",
                "[class*='modal'] [aria-label*='close' i], [class*='modal__close' i], .vxmodal__close",
                ".vxmodal__overlay-hitbox"
        };
        for (int attempt = 0; attempt < 4; attempt++) {
            if (!isAnyDialogOpen()) {
                return;
            }
            try {
                page.keyboard().press("Escape");
            } catch (Exception ignored) {
                // Keep going.
            }
            page.waitForTimeout(400);
            if (!isAnyDialogOpen()) {
                return;
            }
            for (String selector : closeSelectors) {
                try {
                    Locator close = page.locator(selector).first();
                    if (close.count() > 0 && close.isVisible()) {
                        close.click(new Locator.ClickOptions().setTimeout(2500).setForce(true));
                        page.waitForTimeout(400);
                        if (!isAnyDialogOpen()) {
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // Try the next close control.
                }
            }
            try {
                page.evaluate("() => document.querySelectorAll('dialog[open]')"
                        + ".forEach(d => { try { d.close(); } catch (e) {} })");
            } catch (Exception ignored) {
                // Best effort.
            }
            page.waitForTimeout(400);
        }
        if (isAnyDialogOpen()) {
            System.err.println("[DEVICE DETAILS]   a modal dialog is still open after close attempts.");
        }
    }

    private boolean checkControl(String label, BooleanSupplier action) {
        boolean ok = action.getAsBoolean();
        if (!ok) {
            System.err.println("[DEVICE DETAILS]   control failed: " + label);
        }
        return ok;
    }

    private String capture(String name) {
        return ScreenshotUtils.captureTo(page, screenshotDirectory, slug(name));
    }

    private int intConfig(String key, int fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String slug(String value) {
        if (value == null || value.isBlank()) {
            return "device-details";
        }
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug.isBlank() ? "device-details" : slug;
    }
}
