package com.vigilx.pages;

import java.util.Locale;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Infra Management -&gt; Application Settings -&gt; Device: opens the Device page
 * ({@link #openDevice()}) and dynamically exercises its "Enable QC" / "Enable VA Alerts" switches
 * ({@link #validateEnableQc()}, {@link #validateEnableVaAlerts()}), reached via
 * {@link InfraPage#openApplicationSettings()}.
 *
 * <p>Neither switch's starting state is assumed - {@link #toggleAndValidate} reads it first via
 * {@link Locator#isChecked()}. A switch that starts ON is disabled then re-enabled (exercising
 * both the "Disable" and "Enable" confirmation labels, exactly like the recorded flow); one that
 * starts OFF only needs the single Enable. Either way the switch ends ON, which the caller
 * verifies. Both switches share this one implementation - {@link #validateEnableQc()} and
 * {@link #validateEnableVaAlerts()} are each a one-line call into it - so the dynamic
 * on/off/confirm/API-validate logic is written once, not duplicated per switch.
 *
 * <p>Every confirm click is validated against its background save API using
 * {@link SoakUiUtils#clickAndWaitForResponse}, the same click-and-wait-for-response mechanism
 * every other soak-safe page in this package already uses - not a new one. HTTP status is a hard
 * pass/fail signal; the request payload and response body are also checked (best effort, since
 * the exact request/response shape is not documented here) for the toggle's new boolean state.
 *
 * <p>Contract: never throws into the caller. Every failure is logged and returned as
 * {@code false} so the soak continues.
 */
public class ApplicationSettingsPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public ApplicationSettingsPage(Page page) {
        super(page);
    }

    /** Clicks "Device" (the Application Settings sub-page holding the QC/VA Alerts switches). */
    public boolean openDevice() {
        Locator device = page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Device").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(device, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[APPLICATION SETTINGS] 'Device' link not found.");
            return false;
        }
        try {
            device.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            System.out.println("[APPLICATION SETTINGS] Device page opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[APPLICATION SETTINGS] 'Device' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Dynamically toggles "Enable QC" (see class doc) and validates its save API. */
    public boolean validateEnableQc() {
        return toggleAndValidate("Enable QC");
    }

    /** Dynamically toggles "Enable VA Alerts" (see class doc) and validates its save API. */
    public boolean validateEnableVaAlerts() {
        return toggleAndValidate("Enable VA Alerts");
    }

    // ---------------------------------------------------------------------
    // Shared dynamic toggle + API validation - used by both switches above
    // ---------------------------------------------------------------------

    /**
     * @return {@code true} only when every toggle click, confirm and API check along the way
     *         passed and the switch reads ON afterward
     */
    private boolean toggleAndValidate(String switchLabel) {
        Locator toggle = page.getByRole(AriaRole.SWITCH,
                new Page.GetByRoleOptions().setName(switchLabel).setExact(false)).first();
        if (!SoakUiUtils.waitVisible(toggle, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[APPLICATION SETTINGS]   '" + switchLabel + "' switch not found.");
            return false;
        }

        boolean initiallyOn;
        try {
            initiallyOn = toggle.isChecked();
        } catch (Exception exception) {
            System.err.println("[APPLICATION SETTINGS]   Could not read '" + switchLabel + "' state: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        System.out.println("[APPLICATION SETTINGS]   '" + switchLabel + "' initial state: "
                + (initiallyOn ? "ON" : "OFF"));

        boolean allPassed;
        if (initiallyOn) {
            allPassed = clickAndConfirm(toggle, switchLabel, "Disable", false);
            allPassed = clickAndConfirm(toggle, switchLabel, "Enable", true) && allPassed;
        } else {
            allPassed = clickAndConfirm(toggle, switchLabel, "Enable", true);
        }

        boolean finalOn;
        try {
            finalOn = toggle.isChecked();
        } catch (Exception exception) {
            finalOn = false;
        }
        System.out.println("[APPLICATION SETTINGS]   '" + switchLabel + "' final state: "
                + (finalOn ? "ON" : "OFF") + " (expected ON)");
        return allPassed && finalOn;
    }

    /**
     * Clicks {@code toggle}, confirms {@code confirmLabel} ("Enable"/"Disable") in the dialog that
     * opens, and validates the background save API the confirm click fires.
     */
    private boolean clickAndConfirm(Locator toggle, String switchLabel, String confirmLabel, boolean expectedState) {
        try {
            toggle.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[APPLICATION SETTINGS]   '" + switchLabel + "' toggle could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }

        Locator dialog = page.getByRole(AriaRole.DIALOG).first();
        if (!SoakUiUtils.waitVisible(dialog, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[APPLICATION SETTINGS]   '" + switchLabel + "' (" + confirmLabel
                    + "): no confirmation dialog opened.");
            return false;
        }

        Locator confirmButton = dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(confirmLabel).setExact(true)).first();
        if (confirmButton.count() == 0) {
            System.err.println("[APPLICATION SETTINGS]   '" + switchLabel + "': confirmation dialog had no '"
                    + confirmLabel + "' button.");
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }

        Response response = SoakUiUtils.clickAndWaitForResponse(
                page, confirmButton, this::isApplicationSettingResponse, 15000);
        SoakUiUtils.closeOpenDialogs(page);

        if (response == null) {
            System.err.println("[APPLICATION SETTINGS]   '" + switchLabel + "' (" + confirmLabel
                    + "): no matching API response within 15s.");
            return false;
        }

        int status = response.status();
        boolean statusOk = status >= 200 && status < 300;
        boolean payloadLooksRight = containsExpectedState(SoakUiUtils.safeRequestBody(response), expectedState);
        boolean responseLooksRight = containsExpectedState(SoakUiUtils.safeResponseBody(response), expectedState);

        System.out.println("[APPLICATION SETTINGS]   " + switchLabel + " " + confirmLabel + " API: "
                + SoakUiUtils.safeMethod(response) + " " + SoakUiUtils.shortPath(response.url()) + " -> " + status
                + " (" + (statusOk ? "PASS" : "FAIL") + ") | request payload reflects "
                + expectedState + ": " + payloadLooksRight + " | response body reflects "
                + expectedState + ": " + responseLooksRight);

        if (!statusOk) {
            System.err.println("[APPLICATION SETTINGS]   " + switchLabel + " " + confirmLabel
                    + " FAILED (HTTP " + status + ").");
        }
        // Status is the hard pass/fail signal - the exact request/response field names are not
        // documented here, so the payload/response checks above are logged for evidence rather
        // than failing the run on their own if the wording does not match exactly what was guessed.
        return statusOk;
    }

    /** Loose, best-effort check that {@code text} carries {@code expectedState}'s boolean literal. */
    private boolean containsExpectedState(String text, boolean expectedState) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(String.valueOf(expectedState));
    }

    /** True for the write request that backs an Enable QC / Enable VA Alerts confirm. Broad on purpose. */
    private boolean isApplicationSettingResponse(Response response) {
        try {
            String method = response.request().method();
            String url = response.url().toLowerCase(Locale.ROOT);
            boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
            return write && (url.contains("device") || url.contains("application-setting")
                    || url.contains("app-setting") || url.contains("qc") || url.contains("alert")
                    || url.contains("config") || url.contains("setting"));
        } catch (Exception exception) {
            return false;
        }
    }
}
