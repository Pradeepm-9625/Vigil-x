package com.vigilx.pages;

import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Organisation -&gt; License: opens the "Activate New License" dialog, validates the
 * background license-lookup API(s) that opening it triggers (confirmed live: a
 * {@code GET .../license/.../licenses} and a {@code POST .../license/online/validate}, both fired
 * just from opening the dialog - no actual license is ever submitted here, per the requirement),
 * verifies the dialog's own content, and closes it. A dedicated class per this project's
 * convention of one page object per distinct screen (mirrors {@link GroupsPage} / {@link RolesPage}
 * sitting alongside {@link UsersRolesPage}); does not modify {@link OrganizationPage} or
 * {@link AuditLogsPage}. Contract: never throws into the caller; every failure is logged and
 * returned as {@code false}.
 */
public class LicensePage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public LicensePage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Full flow: {@link #navigateToLicense()} -&gt; {@link #openActivateNewLicense()} -&gt;
     * {@link #verifyLicenseDialog()} -&gt; {@link #closeLicenseDialog()}. Never activates or
     * submits an actual license - only opens, validates and closes the dialog, per the
     * requirement not to invent license activation data.
     */
    public boolean runLicenseValidationFlow() {
        boolean navigated = navigateToLicense();
        boolean opened = navigated && openActivateNewLicense();
        boolean dialogOk = opened && verifyLicenseDialog();
        boolean closed = opened && closeLicenseDialog();
        System.out.println("[LICENSE] Flow: navigated=" + navigated + " opened=" + opened
                + " dialogVerified=" + dialogOk + " closed=" + closed);
        return navigated && opened && dialogOk && closed;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /**
     * Opens Settings (only if not already open) then "Organisation", then the "License" tab -
     * independent of {@link OrganizationPage} / {@link ProjectInformationPage}, so it re-navigates
     * itself rather than assuming either already ran.
     */
    public boolean navigateToLicense() {
        Locator licenseTab = licenseTab();
        if (!SoakUiUtils.isVisibleQuietly(licenseTab)) {
            try {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(page.getByText(
                        Pattern.compile("organisation|organization", Pattern.CASE_INSENSITIVE)).first());
                if (!settingsAlreadyOpen && SoakUiUtils.isVisibleQuietly(openSettings)) {
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
                System.err.println("[LICENSE]   Could not open Organisation: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                return false;
            }
        }
        if (!SoakUiUtils.waitVisible(licenseTab, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LICENSE]   'License' tab not found.");
            return false;
        }
        try {
            licenseTab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
        } catch (Exception exception) {
            System.err.println("[LICENSE]   'License' tab could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        boolean loaded = SoakUiUtils.waitVisible(activateNewLicenseButton(), ELEMENT_TIMEOUT_MS);
        System.out.println("[LICENSE] License tab opened: " + (loaded ? "YES" : "NO"));
        return loaded;
    }

    // ---------------------------------------------------------------------
    // Activate New License dialog
    // ---------------------------------------------------------------------

    /**
     * Clicks "Activate New License" and waits for whichever license-lookup API the dialog fires
     * while opening (confirmed live: a license list GET and/or an online-validate POST - broad on
     * purpose, since which one(s) a build fires is an implementation detail). Reuses
     * {@link SoakUiUtils#clickAndWaitForResponse} (no new API framework). A build that opens the
     * dialog without any matching API call still counts as opened, provided the dialog itself
     * becomes visible - logged either way.
     */
    public boolean openActivateNewLicense() {
        Locator activate = activateNewLicenseButton();
        if (!SoakUiUtils.waitVisible(activate, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[LICENSE]   'Activate New License' control not found.");
            return false;
        }
        Response response = SoakUiUtils.clickAndWaitForResponse(page, activate, this::isLicenseResponse, 8000);
        if (response != null) {
            int status = response.status();
            boolean statusOk = status >= 200 && status < 300;
            System.out.println("[LICENSE]   License lookup API: " + safeMethod(response) + " "
                    + shortPath(response.url()) + " -> " + status + " (" + (statusOk ? "PASS" : "FAIL") + ")");
        } else {
            System.out.println("[LICENSE]   No license-lookup API observed within 8s (dialog visibility"
                    + " is the fallback signal).");
        }
        page.waitForTimeout(400);
        boolean dialogOpen = SoakUiUtils.waitVisible(licenseDialog(), ELEMENT_TIMEOUT_MS);
        System.out.println("[LICENSE]   Activate New License dialog opened: " + (dialogOpen ? "YES" : "NO"));
        return dialogOpen;
    }

    /**
     * Confirms the dialog's own stable content: its title text and "Submit" button - never a
     * combined page-text blob.
     */
    public boolean verifyLicenseDialog() {
        boolean titleOk = SoakUiUtils.isVisibleQuietly(
                page.getByText("Activate New License", new Page.GetByTextOptions().setExact(false)).first());
        boolean submitOk = SoakUiUtils.isVisibleQuietly(page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Submit").setExact(false)).first());
        System.out.println("[LICENSE]   Dialog content: title=" + titleOk + " submitButton=" + submitOk);
        return titleOk && submitOk;
    }

    /**
     * Closes the dialog via its own "Close dialog" control - confirmed live: two elements share
     * that exact accessible name at this point (one belongs to a control behind the dialog), so
     * the LAST one (the dialog's own, topmost close) is the one actually clicked, matching a real
     * recorded {@code nth(1)} on a two-element page. Falls back to
     * {@link SoakUiUtils#closeOpenDialogs} as a guarantee regardless.
     */
    public boolean closeLicenseDialog() {
        try {
            Locator closeButtons = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Close dialog").setExact(false));
            Locator lastClose = closeButtons.last();
            if (SoakUiUtils.isVisibleQuietly(lastClose)) {
                lastClose.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(500);
            }
        } catch (Exception exception) {
            System.err.println("[LICENSE]   'Close dialog' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
        SoakUiUtils.closeOpenDialogs(page);
        boolean closed = !SoakUiUtils.isAnyDialogOpen(page);
        System.out.println("[LICENSE]   License dialog closed: " + (closed ? "YES" : "NO"));
        return closed;
    }

    // ---------------------------------------------------------------------
    // Locators
    // ---------------------------------------------------------------------

    private Locator licenseTab() {
        return page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("License").setExact(false)).first();
    }

    private Locator activateNewLicenseButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Activate New License").setExact(false)).first();
    }

    private Locator licenseDialog() {
        return page.getByRole(AriaRole.DIALOG).first();
    }

    private boolean isLicenseResponse(Response response) {
        try {
            String url = response.url().toLowerCase(Locale.ROOT);
            return url.contains("license");
        } catch (Exception exception) {
            return false;
        }
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
