package com.vigilx.tools;

import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.ProjectHierarchyValidation;

/**
 * Throwaway probe: runs the real, fixed {@link ProjectHierarchyValidation#validateRandomNodeAndSite()}
 * end-to-end against the live app, to confirm the vxdd-dropdown and Save-button fixes actually work
 * live rather than just compiling.
 */
public final class ProjectHierarchyLifecycleProbe {
    private ProjectHierarchyLifecycleProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));

            ProjectHierarchyValidation projectHierarchy = new ProjectHierarchyValidation(page);
            projectHierarchy.open();

            boolean result = projectHierarchy.validateRandomNodeAndSite();
            System.out.println("PROBE: validateRandomNodeAndSite() = " + result);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }
}
