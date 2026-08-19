package com.vigilx.pages;

import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.LoggerUtils;

public class ForgotPasswordPage extends BasePage {

    private static final Logger logger =
            LoggerUtils.getLogger(ForgotPasswordPage.class);

    //=========================================================
    // Locators
    //=========================================================

    private final Locator txtEmail;

    private final Locator btnSendVerificationCode;

    private final Locator btnBack;

    private final Locator lblOtpTimer;

    private final Locator btnResend;

    //=========================================================
    // Constructor
    //=========================================================

    public ForgotPasswordPage(Page page) {

        super(page);

        txtEmail = page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions()
                        .setName("Email"));

        btnSendVerificationCode = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Send verification code"));

        btnBack = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Back"));

        lblOtpTimer = page.getByText("Enter OTP within");

        btnResend = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Resend"));

    }

    //=========================================================
    // Actions
    //=========================================================

    public ForgotPasswordPage enterEmail(String email) {

        logger.info("Entering Email");

        actions.type(txtEmail, email);

        return this;

    }

    public OTPPage clickSendVerificationCode() {

        logger.info("Clicking Send Verification Code");

        actions.click(btnSendVerificationCode);
        waitAfterPageNavigation();

        return new OTPPage(page);

    }

    public LoginPage clickBack() {

        logger.info("Clicking Back Button");

        actions.click(btnBack);
        waitAfterPageNavigation();

        return new LoginPage(page);

    }

    public ForgotPasswordPage clickResendOTP() {

        logger.info("Clicking Resend OTP");

        actions.click(btnResend);

        return this;

    }

    //=========================================================
    // Validations
    //=========================================================

    public boolean isForgotPasswordPageDisplayed() {

        return actions.isVisible(txtEmail);

    }
    public boolean isSendVerificationButtonDisplayed() {

        return actions.isVisible(btnSendVerificationCode);

    }

    public boolean isOtpTimerDisplayed() {

        return actions.isVisible(lblOtpTimer);

    }

    public String getPageTitle() {

        return actions.getTitle();

    }

}