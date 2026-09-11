package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.OrganizationPage;
import com.vigilx.pages.ProjectInformationPage;

/** Throwaway diagnostic: exercises the real ProjectInformationPage.runProjectInformationFlow() end to end. */
public final class ProjectInformationMethodProbe {

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            page.waitForTimeout(1500);

            OrganizationPage organization = new OrganizationPage(page);
            boolean navigated = organization.navigateToOrganization();
            System.out.println("PROBE: navigateToOrganization=" + navigated);
            if (!navigated) {
                return;
            }

            boolean logoOk = organization.uploadLogo(
                    ConfigReader.getOrDefault("organisation.logo.path", "src/test/resources/Logo.jpg"));
            System.out.println("PROBE: organization.uploadLogo=" + logoOk);

            ProjectInformationPage projectInfo = new ProjectInformationPage(page);
            com.vigilx.pages.ProjectInformationData data = new com.vigilx.pages.ProjectInformationData(
                    ConfigReader.getOrDefault("organisation.logo.path", "src/test/resources/Logo.jpg"),
                    ConfigReader.getOrDefault("project.information.description",
                            "Detect events in real time, integrate with other city systems, and route.a"),
                    ConfigReader.getOrDefault("project.information.description.final",
                            "Detect events in real time, integrate with other city systems, and route. Update"),
                    ConfigReader.getOrDefault("project.information.admin.last.name", "kumar - Update"),
                    ConfigReader.getOrDefault("project.information.address.line1",
                            "Railway Station Road, Madhira Bazar, Hyderabad, Telangana. - Update"));
            boolean result = projectInfo.runProjectInformationFlow(data);
            System.out.println("PROBE runProjectInformationFlow = " + result);
        } catch (Exception exception) {
            System.out.println("PROBE ERROR: " + exception);
            exception.printStackTrace();
        } finally {
            page.waitForTimeout(1500);
            PlaywrightFactory.closeBrowser();
        }
    }
}
