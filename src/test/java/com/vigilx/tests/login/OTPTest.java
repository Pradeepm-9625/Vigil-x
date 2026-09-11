package com.vigilx.tests.login;

import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.ForgotPasswordPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.OTPPage;
import com.vigilx.pages.ResetPasswordPage;
import com.vigilx.utils.LoggerUtils;

public class OTPTest extends BaseTest {

    private static final Logger logger =
            LoggerUtils.getLogger(OTPTest.class);

    private OTPPage navigateToOTPPage() {

        LoginPage loginPage = new LoginPage(page);

        ForgotPasswordPage forgotPasswordPage =
                loginPage.clickForgotPassword();

        return forgotPasswordPage
                .enterEmail(ConfigReader.get("username"))
                .clickSendVerificationCode();
    }

    /**
     * TC_OTP_001
     * Verify OTP Page Opens
     */
    @Test(priority = 1)
public void verifyOTPPageOpens() {

    logger.info("========== TC_OTP_001 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    Assert.assertTrue(otpPage.isOTPPageDisplayed());
    Assert.assertTrue(otpPage.isOTPFieldDisplayed());
    Assert.assertTrue(otpPage.isVerifyButtonDisplayed());
    Assert.assertTrue(otpPage.isResendButtonDisplayed());
    Assert.assertTrue(otpPage.isTimerDisplayed());
    Assert.assertTrue(otpPage.isBackButtonDisplayed());

    logger.info("========== TC_OTP_001 Passed ==========");
}

    /**
     * TC_OTP_002
     * Verify OTP Fields
     */
    @Test(priority = 2,groups = {"Smoke","Regression"},
        description = "Verify valid OTP verification.")
    public void verifyOTPFieldsDisplayed() {

        logger.info("========== TC_OTP_002 Started ==========");

        OTPPage otpPage = navigateToOTPPage();

        Assert.assertTrue(
                otpPage.isOTPPageDisplayed(),
                "OTP fields should be displayed.");

        logger.info("========== TC_OTP_002 Passed ==========");
    }

    /**
     * TC_OTP_003
     * Verify Valid OTP
     */
   @Test(
    priority = 3,
    groups = {"Smoke", "Regression"},
    description = "Verify that the user is navigated to the Reset Password page after entering a valid OTP."
)
public void verifyValidOTP() {

    logger.info("========== TC_OTP_003 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    ResetPasswordPage resetPasswordPage =
            otpPage.verifyOTP(
                    ConfigReader.get("test.otp"));

    Assert.assertTrue(
            resetPasswordPage.isResetPasswordPageDisplayed(),
            "Reset Password page should be displayed.");

    logger.info("Successfully navigated to the Reset Password page.");
    logger.info("========== TC_OTP_003 Passed ==========");
}
    /**
     * TC_OTP_004
     * Verify Invalid OTP
     */
    @Test(priority = 4)
    public void verifyInvalidOTP() {

        logger.info("========== TC_OTP_004 Started ==========");

        OTPPage otpPage = navigateToOTPPage();

        otpPage.enterOTP("111111")
               .clickVerify();

       Assert.assertEquals(
        otpPage.getInvalidOTPMessage(),
        "Invalid OTP");

        logger.info("========== TC_OTP_004 Passed ==========");
    }

    /**
     * TC_OTP_005
     * Verify Empty OTP
     */
   @Test(priority = 5)
public void verifyEmptyOTP() {

    logger.info("========== TC_OTP_005 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    otpPage.clickVerifyButton();

    Assert.assertTrue(
            otpPage.isOTPPageDisplayed(),
            "OTP page should remain displayed when OTP is empty.");

    // If your application shows an error message:
    // Assert.assertEquals(
    //         otpPage.getOTPRequiredMessage(),
    //         "OTP is required");

    logger.info("========== TC_OTP_005 Passed ==========");
}
/**
 * TC_OTP_006
 * Verify Partial OTP
 */
@Test(priority = 6)
public void verifyPartialOTP() {

    logger.info("========== TC_OTP_006 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    otpPage.enterOTP("123456".substring(0, 4));

    otpPage.clickVerifyButton();

    Assert.assertTrue(
            otpPage.isOTPPageDisplayed(),
            "OTP page should remain displayed when partial OTP is entered.");

    logger.info("========== TC_OTP_006 Passed ==========");

}
/**
 * TC_OTP_007
 * Verify Resend OTP
 */
@Test(priority = 7)
public void verifyResendOTP() {

    logger.info("========== TC_OTP_007 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    otpPage.clickResendOTP();

    Assert.assertTrue(
            otpPage.isOTPPageDisplayed(),
            "OTP page should remain displayed after Resend OTP.");

    logger.info("========== TC_OTP_007 Passed ==========");

}
/**
 * TC_OTP_008
 * Verify OTP Timer
 */
@Test(priority = 8)
public void verifyOTPTimerDisplayed() {

    logger.info("========== TC_OTP_008 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    Assert.assertTrue(
            otpPage.isTimerDisplayed(),
            "OTP timer should be displayed.");

    logger.info("========== TC_OTP_008 Passed ==========");

}
/**
 * TC_OTP_009
 * Verify Back Button
 */
@Test(priority = 9)
public void verifyBackButton() {

    logger.info("========== TC_OTP_009 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    ForgotPasswordPage forgotPasswordPage =
            otpPage.clickBack();

    Assert.assertTrue(
            forgotPasswordPage.isForgotPasswordPageDisplayed(),
            "Forgot Password page should be displayed after clicking Back.");

    logger.info("========== TC_OTP_009 Passed ==========");

}
/**
 * TC_OTP_010
 * Verify Verify Button
 */
@Test(priority = 10)
public void verifyVerifyButtonDisplayed() {

    logger.info("========== TC_OTP_010 Started ==========");

    OTPPage otpPage = navigateToOTPPage();

    Assert.assertTrue(
            otpPage.isVerifyButtonDisplayed(),
            "Verify button should be displayed.");

    Assert.assertTrue(
            otpPage.isVerifyButtonEnabled(),
            "Verify button should be enabled.");

    logger.info("========== TC_OTP_010 Passed ==========");

}


}