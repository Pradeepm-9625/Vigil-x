package com.vigilx.pages;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Configuration: opens Settings ("Open settings") when not already open, then the
 * "Configuration" left-nav link. Its default landing view is the "Enable QC" / "Enable VA Alerts"
 * feature already implemented in {@link ApplicationSettingsPage} ({@link #exerciseApplicationSettings()}
 * reuses it directly rather than duplicating it); its "Health" tab has its own real functionality
 * ({@link #exerciseHealthTab()}: a "S.M.A.R.T Disk Enable/Disable" switch plus Performance /
 * Infrastructure / Video &amp; Image sub-tabs, each with a working "Save changes"); its
 * "Notification Rules" tab delegates straight to the existing
 * {@link NotificationRulesPage#runNotificationRulesFlow()} ({@link #exerciseNotificationRulesTab()});
 * and its "System Settings" tab's Date &amp; Time Settings (Date Format / Hour Format / Time Zone)
 * are each set to a random option ({@link #exerciseSystemSettingsTab()}). Its "Default Settings"
 * tab is fully covered too ({@link #exerciseDefaultSettingsTab()}): every dropdown discovered
 * dynamically within {@code .default-settings__rows} and set to a random option, "Instant Replay
 * Seconds" probed at its minimum/maximum/a random in-range value plus two out-of-range values, and
 * every toggle switch exercised ON/OFF with one left changed to confirm it persists across a page
 * refresh. Every other tab (E-mail / Scheduled Jobs) is navigation and page-load validation only,
 * per the request: neither has an existing functional test of its own on this specific screen.
 *
 * <p>Contract: never throws into the caller. Every failure is logged and returned as {@code false}
 * so the soak continues.
 */
public class ConfigurationPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    /** Every tab this page exposes, in the order the recorded flow visits them - "Health",
     *  "Notification Rules", "System Settings" and "Default Settings" excluded, since all four have
     *  their own real functionality, exercised by {@link #exerciseHealthTab()} /
     *  {@link #exerciseNotificationRulesTab()} / {@link #exerciseSystemSettingsTab()} /
     *  {@link #exerciseDefaultSettingsTab()} instead. */
    private static final String[] TABS = {
            "E-mail", "Scheduled Jobs"
    };

    public ConfigurationPage(Page page) {
        super(page);
    }

    /** Opens Settings (only if not already open), then the "Configuration" left-nav link. */
    public boolean open() {
        Locator configurationLink = configurationLink();

        boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(configurationLink.first())
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
                System.err.println("[CONFIGURATION] 'Open settings' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        if (!SoakUiUtils.waitVisible(configurationLink, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[CONFIGURATION] 'Configuration' navigation link not found.");
            return false;
        }
        try {
            configurationLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            System.out.println("[CONFIGURATION] Configuration opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION] 'Configuration' link could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Exercises the "Enable QC" / "Enable VA Alerts" switches on Configuration's own default
     * landing view ({@code .application-settings-main}) - confirmed live via a real recording to be
     * the exact same feature already implemented in {@link ApplicationSettingsPage} for its other
     * entry point (Infra Management -&gt; Application Settings -&gt; Device). Reuses that existing
     * implementation directly rather than a new, duplicate one, per the request.
     */
    public boolean exerciseApplicationSettings() {
        Locator mainArea = page.locator(".application-settings-main").first();
        if (SoakUiUtils.waitVisible(mainArea, ELEMENT_TIMEOUT_MS)) {
            try {
                mainArea.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            } catch (Exception exception) {
                System.out.println("[CONFIGURATION]   '.application-settings-main' could not be clicked ("
                        + SoakUiUtils.firstLine(exception.getMessage()) + "); trying the switches directly.");
            }
        } else {
            System.out.println("[CONFIGURATION]   '.application-settings-main' not present; trying the"
                    + " switches directly.");
        }

        ApplicationSettingsPage applicationSettings = new ApplicationSettingsPage(page);
        boolean qcOk = applicationSettings.validateEnableQc();
        boolean vaOk = applicationSettings.validateEnableVaAlerts();
        return qcOk && vaOk;
    }

    /**
     * Exercises the "Health" tab: toggles "S.M.A.R.T Disk Enable/Disable" on then off (no
     * confirmation popup for this one, confirmed live via a real recording) and saves, then visits
     * its own Performance / Infrastructure / Video &amp; Image sub-tabs, each saved in turn - every
     * one of these four screens has its own working "Save changes" control.
     */
    public boolean exerciseHealthTab() {
        Locator healthTab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Health").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(healthTab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   'Health' tab not present; skipping.");
            return true;
        }
        try {
            healthTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   'Health' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        boolean smartDiskOk = toggleSmartDisk();
        boolean savedOk = clickConfigurationSaveChanges("Health");
        boolean performanceOk = visitHealthSubTabAndSave("Performance");
        boolean infrastructureOk = visitHealthSubTabAndSave("Infrastructure");
        boolean videoImageOk = visitHealthSubTabAndSave("Video & Image");
        return smartDiskOk && savedOk && performanceOk && infrastructureOk && videoImageOk;
    }

    /**
     * Toggles "S.M.A.R.T Disk Enable/Disable" on then off, netting back to its original state - no
     * confirmation dialog for this switch (confirmed live via a real recording, unlike Master
     * Configuration's / Device Details' Backup switches). A missing switch is a clean skip.
     */
    private boolean toggleSmartDisk() {
        Locator toggle = page.getByRole(AriaRole.SWITCH,
                new Page.GetByRoleOptions().setName("S.M.A.R.T Disk Enable/Disable").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(toggle, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   'S.M.A.R.T Disk Enable/Disable' switch not present; skipping.");
            return true;
        }
        try {
            toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(300);
            toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(300);
            System.out.println("[CONFIGURATION]   'S.M.A.R.T Disk Enable/Disable' toggled.");
            return true;
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   'S.M.A.R.T Disk Enable/Disable' could not be toggled: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Clicks {@code subTabName} (one of Health's own Performance / Infrastructure / Video & Image
     *  sub-tabs) and saves it. A missing sub-tab is a clean skip, not a failure. */
    private boolean visitHealthSubTabAndSave(String subTabName) {
        Locator subTab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName(subTabName).setExact(false)).first();
        if (!SoakUiUtils.waitVisible(subTab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   '" + subTabName + "' sub-tab not present; skipping.");
            return true;
        }
        try {
            subTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   '" + subTabName + "' sub-tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        return clickConfigurationSaveChanges(subTabName);
    }

    /** Clicks "Save changes" on whichever Configuration (sub-)tab is currently open - shared by
     *  Health and Default Settings, neither duplicating this logic. A missing control is a clean
     *  skip, not a failure. */
    private boolean clickConfigurationSaveChanges(String context) {
        Locator saveCheck = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(saveCheck, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   '" + context + "' 'Save changes' control not present; skipping.");
            return true;
        }
        // The app disables "Save changes" whenever the form has no net change from what is already
        // saved (confirmed live: ending a probe sequence back at the original value, e.g. Instant
        // Replay Seconds settling back on its already-persisted minimum, leaves nothing to save) -
        // that is correct app behavior, not a failed click, so it is treated as a clean no-op rather
        // than retried/failed.
        try {
            if (!saveCheck.isEnabled()) {
                System.out.println("[CONFIGURATION]   '" + context + "' no pending changes to save.");
                return true;
            }
        } catch (Exception ignored) {
            // Best effort - fall through to the click attempt below.
        }
        // One retry on a transient click failure (confirmed live: a just-blurred/re-rendered form
        // control can momentarily detach the save button between the visibility check above and the
        // click below) - the locator is re-queried fresh each attempt rather than reusing a handle
        // that may already be stale.
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Locator save = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
            try {
                save.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(600);
                System.out.println("[CONFIGURATION]   '" + context + "' saved.");
                return true;
            } catch (Exception exception) {
                lastFailure = exception;
                try {
                    if (!save.isEnabled()) {
                        System.out.println("[CONFIGURATION]   '" + context + "' no pending changes to save.");
                        return true;
                    }
                } catch (Exception ignoredInner) {
                    // Best effort.
                }
                page.waitForTimeout(400);
            }
        }
        System.err.println("[CONFIGURATION]   '" + context + "' 'Save changes' could not be clicked: "
                + SoakUiUtils.firstLine(lastFailure == null ? null : lastFailure.getMessage()));
        return false;
    }

    /**
     * Exercises the "Notification Rules" tab by delegating straight to the existing
     * {@link NotificationRulesPage#runNotificationRulesFlow()} - its own {@code open()} already
     * finds "Notification Rules" as either a link or a tab (confirmed live via a real recording to
     * also render as a tab here), and its toggle discovery/flip/API-validation logic is generic
     * (never hardcoded channel/module names), so nothing here duplicates or rewrites any of it.
     */
    public boolean exerciseNotificationRulesTab() {
        return new NotificationRulesPage(page).runNotificationRulesFlow();
    }

    /**
     * Exercises the "System Settings" tab's Date &amp; Time Settings: Date Format, Hour Format and
     * Time Zone, each opened and set to a <strong>random</strong> option - never a fixed target,
     * per the request - confirmed live via a real recording, each is its own "Select"-style
     * dropdown trigger ({@code role="button"}, accessible name the currently shown value:
     * "DD/MM/YYYY" / "Hour" / "Asia/Dubai (UTC+04:00)" when this was last recorded).
     */
    public boolean exerciseSystemSettingsTab() {
        Locator tab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("System Settings").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(tab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   'System Settings' tab not present; skipping.");
            return true;
        }
        try {
            tab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   'System Settings' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        boolean dateFormatOk = selectRandomDropdownOption("Date Format", "DD/MM/YYYY");
        // Hour Format is a persistent setting (confirmed live: an earlier run's own random pick
        // carries over), so its current value is never assumed to still be "Hour" - every value the
        // dropdown can actually hold is tried as a candidate trigger name before falling back to the
        // field's own label.
        boolean hourFormatOk = selectRandomDropdownOption("Hour Format", "Hour", "12 Hour", "24 Hour");
        boolean timeZoneOk = selectRandomDropdownOption("Time Zone", "Asia/Dubai (UTC+04:00)");
        return dateFormatOk && hourFormatOk && timeZoneOk;
    }

    /**
     * Opens a "Select"-style dropdown trigger - a {@code role="button"} whose accessible name is
     * whatever value is currently shown, one of {@code knownCurrentValues} when this was last
     * confirmed live (tried in order; this setting can persist across runs, so the one actually
     * showing now may not be the first), falling back to a search near the field's own label if the
     * environment's current value has since moved on to something else entirely - and picks a
     * random option from whatever it lists, never a fixed target. A missing trigger is a clean
     * skip, not a failure.
     */
    private boolean selectRandomDropdownOption(String fieldLabel, String... knownCurrentValues) {
        Locator trigger = null;
        for (String knownCurrentValue : knownCurrentValues) {
            Locator candidate = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(knownCurrentValue).setExact(true)).first();
            if (SoakUiUtils.isVisibleQuietly(candidate)) {
                trigger = candidate;
                break;
            }
        }
        if (trigger == null) {
            Locator label = page.getByText(fieldLabel, new Page.GetByTextOptions().setExact(true)).first();
            if (SoakUiUtils.isVisibleQuietly(label)) {
                trigger = label.locator("xpath=following::button[1]").first();
            } else {
                trigger = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName(knownCurrentValues[0]).setExact(true)).first();
            }
        }
        if (!SoakUiUtils.waitVisible(trigger, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   '" + fieldLabel + "' dropdown not present; skipping.");
            return true;
        }
        try {
            trigger.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(400);
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   '" + fieldLabel + "' could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator option = randomOpenDropdownOption();
        if (option == null) {
            System.out.println("[CONFIGURATION]   '" + fieldLabel + "' dropdown listed no selectable option.");
            try {
                page.keyboard().press("Escape");
            } catch (Exception ignored) {
                // Best effort.
            }
            return true;
        }
        String chosen;
        try {
            chosen = option.innerText().trim();
            option.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(400);
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   '" + fieldLabel + "' option could not be selected: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        System.out.println("[CONFIGURATION]   '" + fieldLabel + "' set to (random): '" + chosen + "'.");
        return true;
    }

    /**
     * One visible, real option from the currently open dropdown, picked at random - the same
     * {@code .vxdd__menu}/{@code .vxdd__item} portal pattern already confirmed live and used by
     * {@link DeviceDetailsValidation}'s own random-option picker, role-based scanning as the fallback.
     */
    private Locator randomOpenDropdownOption() {
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
                if (item.isVisible()) {
                    String text = item.innerText().trim();
                    if (!text.isBlank()) {
                        visible.add(item);
                    }
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

    // ---------------------------------------------------------------------
    // Default Settings
    // ---------------------------------------------------------------------

    /**
     * Clicks "Default Settings" then runs its full coverage: every dropdown set to a random option
     * ({@link #exerciseDefaultSettingsDropdowns()}), "Instant Replay Seconds" at its minimum/
     * maximum/a random in-range value plus two out-of-range probes
     * ({@link #exerciseInstantReplaySeconds()}), then every toggle switch exercised ON/OFF with one
     * left changed to confirm it survives a page refresh ({@link #exerciseDefaultSettingsToggles()}).
     */
    public boolean exerciseDefaultSettingsTab() {
        Locator tab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Default Settings").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(tab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   'Default Settings' tab not present; skipping.");
            return true;
        }
        try {
            tab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   'Default Settings' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        boolean dropdownsOk = exerciseDefaultSettingsDropdowns();
        boolean instantReplayOk = exerciseInstantReplaySeconds();
        boolean togglesOk = exerciseDefaultSettingsToggles();
        return dropdownsOk && instantReplayOk && togglesOk;
    }

    /**
     * Discovers every dropdown trigger within {@code .default-settings__rows} dynamically -
     * {@code role="button"} elements in each such container, which this build renders only for
     * dropdown triggers (toggle switches are {@code role="switch"}, the Instant Replay field a
     * {@code role="textbox"}, both naturally excluded) - never hardcoded to specific field
     * names/values, since each one's current value varies by environment and persists across runs.
     * Confirmed live: {@code .default-settings__rows} is not one single container but one per
     * settings group ("Live View", "Playback", ...), so every match is scanned, not just the first -
     * scanning only the first one silently missed every group after it. Each dropdown is opened, set
     * to a random option, and the newly shown value confirmed, then the whole tab is saved once.
     */
    public boolean exerciseDefaultSettingsDropdowns() {
        Locator containers = page.locator(".default-settings__rows");
        int containerCount = containers.count();
        if (containerCount == 0) {
            System.out.println("[CONFIGURATION]   '.default-settings__rows' not present; skipping dropdown"
                    + " discovery.");
            return true;
        }
        System.out.println("[CONFIGURATION]   Default Settings: " + containerCount
                + " settings group(s) (.default-settings__rows) found.");

        boolean allOk = true;
        int dropdownsFound = 0;
        for (int groupIndex = 0; groupIndex < containerCount; groupIndex++) {
            Locator container = containers.nth(groupIndex);
            int probed = Math.min(container.getByRole(AriaRole.BUTTON).count(), 40);
            for (int index = 0; index < probed; index++) {
                // Re-queried fresh every iteration: once a value changes the trigger's own text
                // changes too, and a stale handle from before a prior save/reflow must never be reused.
                Locator trigger = containers.nth(groupIndex).getByRole(AriaRole.BUTTON).nth(index);
                String before;
                try {
                    before = trigger.innerText().trim();
                } catch (Exception exception) {
                    continue;
                }
                if (before.isBlank() || before.equalsIgnoreCase("Save changes") || before.equalsIgnoreCase("Save")) {
                    continue;
                }
                try {
                    trigger.click(new Locator.ClickOptions().setTimeout(3000));
                } catch (Exception exception) {
                    continue;
                }
                page.waitForTimeout(300);

                Locator option = randomOpenDropdownOption();
                if (option == null) {
                    // Not actually a dropdown (or it listed nothing) - close anything stray and move on.
                    try {
                        page.keyboard().press("Escape");
                    } catch (Exception ignored) {
                        // Best effort.
                    }
                    continue;
                }
                dropdownsFound++;
                String chosen;
                try {
                    chosen = option.innerText().trim();
                    option.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(400);
                } catch (Exception exception) {
                    System.err.println("[CONFIGURATION]   Default Settings dropdown '" + before
                            + "' option could not be selected: " + SoakUiUtils.firstLine(exception.getMessage()));
                    allOk = false;
                    continue;
                }
                boolean shown = SoakUiUtils.isVisibleQuietly(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(chosen).setExact(true)).first());
                System.out.println("[CONFIGURATION]   Default Settings dropdown '" + before + "' -> random '"
                        + chosen + "' shown=" + shown);
                allOk &= shown;
            }
        }
        System.out.println("[CONFIGURATION]   Default Settings: " + dropdownsFound + " real dropdown(s) exercised.");

        boolean savedOk = clickConfigurationSaveChanges("Default Settings dropdowns");
        return allOk && savedOk;
    }

    /**
     * Exercises "Instant Replay Seconds (10-60)": sets it to the minimum (10), the maximum (60), and
     * one random value strictly between them - each must be accepted and shown as-is - then one
     * value below the minimum and one above the maximum, confirming the app does not silently accept
     * either (clamped back into range, or rejected/left unchanged both count as handled correctly).
     * Leaves the field at the minimum afterward regardless of the out-of-range probes.
     */
    public boolean exerciseInstantReplaySeconds() {
        Locator textbox = page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions()
                .setName(Pattern.compile("instant replay seconds", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(textbox, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   'Instant Replay Seconds' control not present; skipping.");
            return true;
        }

        int min = 10;
        int max = 60;
        int random = min + 1 + new java.util.Random().nextInt(Math.max(1, max - min - 1));

        boolean minOk = setInstantReplayAndVerify(textbox, min);
        boolean maxOk = setInstantReplayAndVerify(textbox, max);
        boolean randomOk = setInstantReplayAndVerify(textbox, random);
        boolean belowRangeOk = probeOutOfRangeInstantReplay(textbox, min - 1);
        boolean aboveRangeOk = probeOutOfRangeInstantReplay(textbox, max + 1);
        setInstantReplayAndVerify(textbox, min);

        boolean savedOk = clickConfigurationSaveChanges("Instant Replay Seconds");
        return minOk && maxOk && randomOk && belowRangeOk && aboveRangeOk && savedOk;
    }

    /** Fills an in-range {@code value} into the Instant Replay textbox and confirms it is shown
     *  exactly as typed. */
    private boolean setInstantReplayAndVerify(Locator textbox, int value) {
        try {
            textbox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS).setClickCount(3));
            textbox.fill(String.valueOf(value));
            textbox.press("Tab");
            page.waitForTimeout(400);
            String shown = textbox.inputValue().trim();
            boolean ok = shown.equals(String.valueOf(value));
            System.out.println("[CONFIGURATION]   Instant Replay Seconds = " + value + " (in range): shown='"
                    + shown + "' " + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   Instant Replay Seconds = " + value + " could not be set: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Fills an out-of-range {@code value} and - unless it is already clamped the moment the field
     * loses focus - attempts to actually save it, treating the probe as "handled correctly" (PASS)
     * when ANY of these hold: the field stops showing the typed out-of-range value (clamped, either
     * immediately or after the save attempt), the "Save" control is disabled while it is showing, or
     * the save attempt's own toast reads as an error (not a success). Only a save that genuinely
     * appears to SUCCEED while the out-of-range value is still shown counts as a real FAIL - the app
     * accepting an invalid value with no validation at all.
     */
    private boolean probeOutOfRangeInstantReplay(Locator textbox, int value) {
        try {
            textbox.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS).setClickCount(3));
            textbox.fill(String.valueOf(value));
            textbox.press("Tab");
            page.waitForTimeout(400);

            String shownBeforeSave = textbox.inputValue().trim();
            if (!shownBeforeSave.equals(String.valueOf(value))) {
                System.out.println("[CONFIGURATION]   Instant Replay Seconds = " + value
                        + " (out of range [10,60]): clamped immediately to '" + shownBeforeSave + "' PASS");
                return true;
            }

            Locator save = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
            boolean saveDisabled = false;
            try {
                saveDisabled = SoakUiUtils.isVisibleQuietly(save) && !save.isEnabled();
            } catch (Exception ignored) {
                // Best effort.
            }
            if (saveDisabled) {
                System.out.println("[CONFIGURATION]   Instant Replay Seconds = " + value
                        + " (out of range [10,60]): 'Save' disabled - PASS (rejected)");
                return true;
            }

            String toastBefore = SoakUiUtils.readToastText(page);
            if (SoakUiUtils.isVisibleQuietly(save)) {
                try {
                    save.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                } catch (Exception ignored) {
                    // Best effort - the toast/value checks below are the real signal.
                }
            }
            page.waitForTimeout(1000);
            String toast = SoakUiUtils.readToastText(page);
            String shownAfterSave = textbox.inputValue().trim();

            boolean toastLooksBad = !toast.isBlank() && !toast.equals(toastBefore)
                    && Pattern.compile("fail|error|invalid|must be|between|range|exceed",
                            Pattern.CASE_INSENSITIVE).matcher(toast).find();
            boolean clampedAfterSave = !shownAfterSave.equals(String.valueOf(value));
            boolean handled = clampedAfterSave || toastLooksBad;

            System.out.println("[CONFIGURATION]   Instant Replay Seconds = " + value
                    + " (out of range [10,60]): shown='" + shownAfterSave + "' toast='" + toast + "' "
                    + (handled ? "PASS (rejected/clamped)" : "FAIL (accepted out-of-range)"));
            return handled;
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   Instant Replay Seconds = " + value
                    + " (out of range) could not be probed: " + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Discovers every toggle switch within {@code .default-settings__rows} dynamically ({@code
     * role="switch"} - never hardcoded to specific switch names), clicks each ON then OFF (netting
     * back to its original state) verifying the state actually flips each time, saves, then - for
     * the first toggle found - leaves it changed (not netted back), reloads the page and confirms
     * that changed state survived the refresh, per the request.
     */
    public boolean exerciseDefaultSettingsToggles() {
        Locator containers = page.locator(".default-settings__rows");
        if (containers.count() == 0) {
            System.out.println("[CONFIGURATION]   '.default-settings__rows' not present; skipping toggle"
                    + " discovery.");
            return true;
        }
        // Confirmed live: .default-settings__rows is one container per settings group ("Live View",
        // "Playback", ...), not a single container - every switch across every group is matched by
        // scoping to the whole set rather than just the first one, which silently missed every group
        // after it.
        Locator switches = containers.getByRole(AriaRole.SWITCH);
        int count = Math.min(switches.count(), 20);
        System.out.println("[CONFIGURATION]   Default Settings: " + count + " toggle(s) found.");
        if (count == 0) {
            return true;
        }

        boolean allOk = true;
        String persistName = null;
        boolean persistExpected = false;
        for (int index = 0; index < count; index++) {
            Locator toggle = switches.nth(index);
            String name;
            boolean initiallyOn;
            try {
                name = toggle.getAttribute("aria-label");
                if (name == null || name.isBlank()) {
                    name = "toggle #" + index;
                }
                initiallyOn = toggle.isChecked();
            } catch (Exception exception) {
                continue;
            }
            try {
                toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300);
                boolean afterFirst = toggle.isChecked();
                boolean firstOk = afterFirst != initiallyOn;

                toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(300);
                boolean afterSecond = toggle.isChecked();
                boolean secondOk = afterSecond == initiallyOn;

                System.out.println("[CONFIGURATION]   Toggle '" + name + "': " + initiallyOn + " -> " + afterFirst
                        + " -> " + afterSecond + " (expected back to " + initiallyOn + ") "
                        + (firstOk && secondOk ? "PASS" : "FAIL"));
                allOk &= firstOk && secondOk;

                if (persistName == null) {
                    persistName = name;
                    // Leave THIS one toggle flipped (not netted back) so there is a real state
                    // change to verify persists after Save + refresh, per the request.
                    toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    page.waitForTimeout(300);
                    persistExpected = toggle.isChecked();
                }
            } catch (Exception exception) {
                System.err.println("[CONFIGURATION]   Toggle '" + name + "' could not be exercised: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                allOk = false;
            }
        }

        boolean savedOk = clickConfigurationSaveChanges("Default Settings toggles");
        boolean persistOk = persistName == null || verifyTogglePersistsAfterRefresh(persistName, persistExpected);
        return allOk && savedOk && persistOk;
    }

    /** Reloads the page, re-opens Configuration -&gt; Default Settings, and confirms the named
     *  toggle's state survived the refresh - per the request. */
    private boolean verifyTogglePersistsAfterRefresh(String toggleName, boolean expectedOn) {
        try {
            page.reload();
            waitAfterPageNavigation();
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   Could not refresh to verify '" + toggleName + "' persistence: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        if (!open()) {
            return false;
        }
        Locator tab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Default Settings").setExact(false)).first();
        if (SoakUiUtils.waitVisible(tab, ELEMENT_TIMEOUT_MS)) {
            try {
                tab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(600);
            } catch (Exception ignored) {
                // Best effort - the toggle lookup below still has its own chance to find it.
            }
        }
        Locator toggle = page.locator(".default-settings__rows").getByRole(AriaRole.SWITCH,
                new Locator.GetByRoleOptions().setName(toggleName).setExact(false)).first();
        boolean matched = false;
        try {
            matched = SoakUiUtils.waitVisible(toggle, ELEMENT_TIMEOUT_MS) && toggle.isChecked() == expectedOn;
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   Could not read '" + toggleName + "' state after refresh: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
        System.out.println("[CONFIGURATION]   Toggle '" + toggleName + "' persisted after refresh (expected "
                + expectedOn + "): " + matched);
        return matched;
    }

    /**
     * Walks every other Configuration tab in turn, confirming each one loads - navigation and
     * page-load validation only (none of these have an existing functional test on this screen). A
     * missing tab is a clean skip, not a failure.
     */
    public boolean visitAllTabs() {
        boolean ok = true;
        for (String tabName : TABS) {
            ok &= visitTab(tabName);
        }
        return ok;
    }

    private boolean visitTab(String tabName) {
        Locator tab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName(tabName).setExact(false)).first();
        if (!SoakUiUtils.waitVisible(tab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[CONFIGURATION]   '" + tabName + "' tab not present; skipping.");
            return true;
        }
        try {
            tab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
            System.out.println("[CONFIGURATION]   '" + tabName + "' tab opened (navigation-only check).");
            return true;
        } catch (Exception exception) {
            System.err.println("[CONFIGURATION]   '" + tabName + "' tab could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private Locator configurationLink() {
        return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("configuration", Pattern.CASE_INSENSITIVE)))
                .first();
    }
}
