package com.vigilx.pages;

import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.LoggerUtils;

/**
 * Login Page Object
 */
public class LoginPage extends BasePage {

    private static final Logger logger =
            LoggerUtils.getLogger(LoginPage.class);

    //=========================================================
    // Locators
    //=========================================================

    private final Locator txtUsername;
    private final Locator txtPassword;
    private final Locator btnLogin;
    private final Locator btnPasswordToggle;
    private final Locator chkRememberMe;
    private final Locator lnkForgotPassword;    
    private final Locator lblLoginError;
    private final Locator lblUsernameRequired;
    private final Locator lblPasswordRequired;
    private final Locator toastMessage;

    //=========================================================
    // Constructor
    //=========================================================

    public LoginPage(Page page) {

        super(page);
        toastMessage = page.locator(".Toastify__toast-body");

        txtUsername = page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Email").setExact(true));

        txtPassword = page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Password").setExact(true));

        btnLogin = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Login").setExact(true));

        btnPasswordToggle = page.locator("button.login-password__toggle, button[aria-label='Toggle password']");

        chkRememberMe = page.locator("input[type='checkbox']").first();

        lnkForgotPassword = page.getByText("Forgot Password");

        lblLoginError = page.getByText("Invalid credentials. Please", new Page.GetByTextOptions().setExact(false));
        lblUsernameRequired = page.getByText("Email is required", new Page.GetByTextOptions().setExact(true));
        lblPasswordRequired = page.getByText("Password field is empty", new Page.GetByTextOptions().setExact(true));
    }

    //=========================================================
    // Page Actions
    //=========================================================

    public LoginPage enterUsername(String username) {

        logger.info("Entering username");

        actions.type(txtUsername, username);

        return this;
    }

    public LoginPage enterPassword(String password) {

        logger.info("Entering password");

        actions.type(txtPassword, password);

        return this;
    }

    public LoginPage clickRememberMe() {

        logger.info("Clicking Remember Me");

        actions.click(chkRememberMe);

        return this;
    }

    public LoginPage togglePassword() {

        logger.info("Toggling password visibility");

        actions.click(btnPasswordToggle);

        return this;
    }

    public LoginPage clickLogin() {

        logger.info("Clicking Login button");

        actions.click(btnLogin);

        return this;
    }

     //=========================================================
    // Forgot Password
    //=========================================================
   

    public ForgotPasswordPage clickForgotPassword() {

    logger.info("Opening Forgot Password Page");

    actions.click(lnkForgotPassword);

    page.waitForLoadState();
    waitAfterPageNavigation();

    return new ForgotPasswordPage(page);
}

    /**
     * Perform Login
     */
   public DashboardPage login(String username, String password) {

    logger.info("Starting Login Process");

    enterUsername(username);

    enterPassword(password);

    clickLogin();

    page.waitForURL("**/dashboard");
    waitAfterPageNavigation();

    logger.info("Successfully navigated to Dashboard Page.");

    return new DashboardPage(page);
}

    public LoginPage attemptLogin(String username, String password) {

    logger.info("Attempting Login");

    enterUsername(username);

    enterPassword(password);

    clickLogin();
    return this;
}
        
     
    //=========================================================
    // Validations
    //=========================================================
public boolean isUsernameDisplayed() {
    try {
        return actions.isVisible(txtUsername);
    } catch (Exception e) {
        return false;
    }
}
   public String getLoginErrorMessage() {
    try {
        if (lblLoginError.isVisible()) {
            return lblLoginError.innerText().trim();
        }
        if (toastMessage.isVisible()) {
            return toastMessage.innerText().trim();
        }
    } catch (Exception e) {
        // ignore and return empty
    }
    return "";
}

public boolean isLoginErrorDisplayed() {
    try {
        lblLoginError.waitFor(new Locator.WaitForOptions().setTimeout(5000));
        return true;
    } catch (Exception e) {
        try {
            toastMessage.waitFor(new Locator.WaitForOptions().setTimeout(1000));
            return toastMessage.innerText().contains("Invalid credentials");
        } catch (Exception ignored) {
            return false;
        }
    }
}


    public boolean isPasswordDisplayed() {

        return actions.isVisible(txtPassword);

    }

    public boolean isLoginButtonDisplayed() {

        return actions.isVisible(btnLogin);

    }
   

    public String getPageTitle() {

        return actions.getTitle();

    }

    public String getCurrentUrl() {

        return actions.getCurrentUrl();

    }
    public boolean isUsernameRequiredMessageDisplayed() {

    return actions.isVisible(lblUsernameRequired);

}

public boolean isPasswordRequiredMessageDisplayed() {

    return actions.isVisible(lblPasswordRequired);

}

public String getUsernameRequiredMessage() {

    return lblUsernameRequired.innerText().trim();

}

public String getPasswordRequiredMessage() {

    return lblPasswordRequired.innerText().trim();

}

}
