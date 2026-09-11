package com.vigilx.pages;

import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.LoggerUtils;


public class OrganizationPage extends BasePage {

    private static final Logger logger =
            LoggerUtils.getLogger(OrganizationPage.class);

    //=========================================================
    // Locators
    //=========================================================

    private final Locator btnEditLogo;
    private final Locator btnEdit;
    private final Locator btnSave;
    private final Locator btnProjectInformation;
    private final Locator lblOrganizationName;
    private final Locator lblOrganizationHeader;
    //=========================================================
    // Constructor
    //=========================================================

    public OrganizationPage(Page page) {

        super(page);
        lblOrganizationName = page.locator("div.org-title");

       btnEditLogo = page.locator("button.edit-logo");

        btnEdit = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Edit")
                        .setExact(true));

        btnSave = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Save"));

        btnProjectInformation = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Project Information"));

        lblOrganizationHeader =
        page.locator("h4.page-title");               

    }

    //=========================================================
    // Validation
    //=========================================================

 public boolean isOrganizationPageLoaded() {

    logger.info("Verifying Organization Page");

    lblOrganizationHeader.waitFor();

    return lblOrganizationHeader.isVisible();
}
   public boolean isOrganizationNameDisplayed() {

    return lblOrganizationName.isVisible();

}
    //=========================================================
    // Actions
    //=========================================================

    public OrganizationPage clickEditLogo() {

        logger.info("Clicking Edit Logo");

        actions.click(btnEditLogo);

        return this;

    }

    public OrganizationPage clickEdit() {

        logger.info("Clicking Edit");

        actions.click(btnEdit);

        return this;

    }

    public OrganizationPage clickSave() {

        logger.info("Clicking Save");

        actions.click(btnSave);

        return this;

    }

    public OrganizationPage clickProjectInformation() {

        logger.info("Opening Project Information");

        actions.click(btnProjectInformation);

        return this;

    }

    //=========================================================
    // Getters
    //=========================================================

    public String getPageTitle() {

        return actions.getTitle();

    }

    public String getCurrentUrl() {

        return actions.getCurrentUrl();

    }
   public String getOrganizationName() {

    return lblOrganizationName.innerText().trim();

}

}