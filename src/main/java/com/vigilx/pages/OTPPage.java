package com.vigilx.pages;

import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.LoggerUtils;

public class OTPPage extends BasePage {

    private static final Logger logger =
            LoggerUtils.getLogger(OTPPage.class);

    //=========================================================
    // Locators
    //=========================================================

    private final Locator txtOTP1;
    private final Locator txtOTP2;
    private final Locator txtOTP3;
    private final Locator txtOTP4;
    private final Locator txtOTP5;
    private final Locator txtOTP6;

    private final Locator btnVerify;
    private final Locator btnResend;
    private final Locator btnBack;
    private final Locator lblOTPTimer;

    //=========================================================
    // Constructor
    //=========================================================

    public OTPPage(Page page) {

        super(page);

        txtOTP1 = page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions()
                        .setName("Enter OTP"));

        txtOTP2 = page.getByRole(AriaRole.TEXTBOX).nth(2);

        txtOTP3 = page.getByRole(AriaRole.TEXTBOX).nth(3);

        txtOTP4 = page.getByRole(AriaRole.TEXTBOX).nth(4);

        txtOTP5 = page.getByRole(AriaRole.TEXTBOX).nth(5);

        txtOTP6 = page.locator("input:nth-child(6)");

        btnVerify = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Verify"));

        btnResend = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Resend"));

        btnBack = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName("Back"));

        lblOTPTimer = page.getByText("Enter OTP within");
    }

    //=========================================================
    // Actions
    //=========================================================

    public OTPPage enterOTP(String otp) {

        logger.info("Entering OTP");

        if (otp.length() != 6) {
            throw new IllegalArgumentException(
                    "OTP should contain exactly 6 digits.");
        }

        actions.type(txtOTP1, String.valueOf(otp.charAt(0)));
        actions.type(txtOTP2, String.valueOf(otp.charAt(1)));
        actions.type(txtOTP3, String.valueOf(otp.charAt(2)));
        actions.type(txtOTP4, String.valueOf(otp.charAt(3)));
        actions.type(txtOTP5, String.valueOf(otp.charAt(4)));
        actions.type(txtOTP6, String.valueOf(otp.charAt(5)));

        return this;
    }
    public OTPPage enterPartialOTP(String otp) {

    logger.info("Entering Partial OTP");

    if (otp.length() > 6) {
        throw new IllegalArgumentException("OTP cannot exceed 6 digits.");
    }

    if (otp.length() > 0) actions.type(txtOTP1, String.valueOf(otp.charAt(0)));
    if (otp.length() > 1) actions.type(txtOTP2, String.valueOf(otp.charAt(1)));
    if (otp.length() > 2) actions.type(txtOTP3, String.valueOf(otp.charAt(2)));
    if (otp.length() > 3) actions.type(txtOTP4, String.valueOf(otp.charAt(3)));
    if (otp.length() > 4) actions.type(txtOTP5, String.valueOf(otp.charAt(4)));
    if (otp.length() > 5) actions.type(txtOTP6, String.valueOf(otp.charAt(5)));

    return this;
}

    public ResetPasswordPage clickVerify() {

        logger.info("Clicking Verify");

        actions.click(btnVerify);

        return new ResetPasswordPage(page);

    }

    public OTPPage clickResendOTP() {

        logger.info("Clicking Resend OTP");

        actions.click(btnResend);

        return this;

    }

    public ForgotPasswordPage clickBack() {

        logger.info("Clicking Back");

        actions.click(btnBack);

        return new ForgotPasswordPage(page);

    }

    public ResetPasswordPage verifyOTP(String otp) {

        enterOTP(otp);

        return clickVerify();

    }

    //=========================================================
    // Validations
    //=========================================================

    public boolean isOTPPageDisplayed() {

        return actions.isVisible(btnVerify);

    }

    public boolean isVerifyButtonDisplayed() {

        return actions.isVisible(btnVerify);

    }

    public boolean isResendButtonDisplayed() {

        return actions.isVisible(btnResend);

    }

    public boolean isTimerDisplayed() {

        return actions.isVisible(lblOTPTimer);

    }

    public String getPageTitle() {

        return actions.getTitle();

    }

    public String getCurrentUrl() {

        return actions.getCurrentUrl();

    }
    public OTPPage clickVerifyButton() {

    logger.info("Clicking Verify Button");

    actions.click(btnVerify);

    return this;

}

public boolean isBackButtonDisplayed() {

    return actions.isVisible(btnBack);

}

public boolean isVerifyButtonEnabled() {

    return btnVerify.isEnabled();

}

public boolean isOTPFieldDisplayed() {

    return actions.isVisible(txtOTP1);

}
private final Locator lblInvalidOTP =
        page.getByText("Invalid OTP");


        public String getInvalidOTPMessage() {

    return lblInvalidOTP.innerText();

}

}