package com.vigilx.tools;

import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.AuditLogsPage;
import com.vigilx.pages.LicensePage;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.OrganizationPage;

/** Throwaway diagnostic: exercises LicensePage.runLicenseValidationFlow() then reused AuditLogsPage. */
public final class LicenseFlowMethodProbe {

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            page.waitForTimeout(1500);

            OrganizationPage organization = new OrganizationPage(page);
            boolean navigated = organization.navigateToOrganization();
            System.out.println("PROBE: navigateToOrganization=" + navigated);

            LicensePage license = new LicensePage(page);
            boolean licenseOk = license.runLicenseValidationFlow();
            System.out.println("PROBE runLicenseValidationFlow = " + licenseOk);

            AuditLogsPage auditLogs = new AuditLogsPage(page);
            boolean auditOk = auditLogs.runAuditLogsExportFlow();
            System.out.println("PROBE runAuditLogsExportFlow (Organisation tab) = " + auditOk);
        } catch (Exception exception) {
            System.out.println("PROBE ERROR: " + exception);
            exception.printStackTrace();
        } finally {
            page.waitForTimeout(1500);
            PlaywrightFactory.closeBrowser();
        }
    }
}
