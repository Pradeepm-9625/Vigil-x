package com.vigilx.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Dashboard Page Object
 *
 * Contains dashboard related actions and validations.
 *
 * @author Pradeep
 */
public class DashboardPage extends BasePage {

    //====================================================
    // Locators
    //====================================================

    private final Locator profileIcon;
    private final Locator logoutButton;

    //====================================================
    // Constructor
    //====================================================

    public DashboardPage(Page page) {

        super(page);

        profileIcon = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("PM Pradeep Mm Admin").setExact(false))
                .or(page.getByRole(
                        AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Profile").setExact(false)))
                .first();

        logoutButton = page.getByText("Log Out", new Page.GetByTextOptions().setExact(false)).first();

    }

    //====================================================
    // Validation
    //====================================================

    /**
     * Verify Dashboard Loaded
     */
    public boolean isDashboardLoaded() {
        return page.url().matches(".*/dashboard(?:[/?#].*)?$");

    }

    /**
     * Verify Profile Icon
     */
    public boolean isProfileVisible() {

        return actions.isVisible(profileIcon);

    }

    //====================================================
    // Actions
    //====================================================

    /**
     * Click Profile
     */
    public DashboardPage clickProfile() {

        actions.click(profileIcon);

        return this;

    }

    /**
     * Logout
     */
    public LoginPage logout() {

        clickProfile();
        logoutButton.waitFor(new Locator.WaitForOptions().setTimeout(15000));
        actions.click(logoutButton);

        page.waitForURL("**/onboarding");

        return new LoginPage(page);

    }

    /**
     * Get Dashboard Title
     */
    public String getTitle() {

        return actions.getTitle();

    }

    /**
     * Get Current URL
     */
    public String getCurrentUrl() {

        return actions.getCurrentUrl();

    }

}
