package com.vigilx.pages;

import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.LoggerUtils;

public class ResetPasswordPage extends BasePage {

    private static final Logger logger =
            LoggerUtils.getLogger(ResetPasswordPage.class);

    //====================================================
    // Locators
    //====================================================

    private final Locator txtNewPassword;
    private final Locator txtConfirmPassword;

    private final Locator btnResetPassword;

    private final Locator btnShowPassword;

    //====================================================
    // Constructor
    //====================================================

    public ResetPasswordPage(Page page) {

        super(page);

        txtNewPassword = page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions()
                        .setName("New Password"));

        txtConfirmPassword = page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions()
                        .setName("Confirm Password"));

        btnResetPassword = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Reset password"));

        btnShowPassword = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Show password"));

    }

    //====================================================
    // Actions
    //====================================================

    public ResetPasswordPage enterNewPassword(String password) {

        logger.info("Entering New Password");

        actions.type(txtNewPassword, password);

        return this;

    }

    public ResetPasswordPage enterConfirmPassword(String password) {

        logger.info("Entering Confirm Password");

        actions.type(txtConfirmPassword, password);

        return this;

    }

    public ResetPasswordPage clickShowPassword() {

        logger.info("Clicking Show Password");

        actions.click(btnShowPassword);

        return this;

    }

    public LoginPage clickResetPassword() {

        logger.info("Clicking Reset Password");

        actions.click(btnResetPassword);
        waitAfterPageNavigation();

        return new LoginPage(page);

    }

    public LoginPage resetPassword(
            String newPassword,
            String confirmPassword) {

        enterNewPassword(newPassword);

        enterConfirmPassword(confirmPassword);

        return clickResetPassword();

    }

    //====================================================
    // Validations
    //====================================================

    public boolean isResetPasswordPageDisplayed() {

        return actions.isVisible(btnResetPassword);

    }

    public boolean isResetPasswordButtonDisplayed() {

        return actions.isVisible(btnResetPassword);

    }

    public boolean isNewPasswordDisplayed() {

        return actions.isVisible(txtNewPassword);

    }

    public boolean isConfirmPasswordDisplayed() {

        return actions.isVisible(txtConfirmPassword);

    }

    public String getPageTitle() {

        return actions.getTitle();

    }

    public String getCurrentUrl() {

        return actions.getCurrentUrl();

    }

}