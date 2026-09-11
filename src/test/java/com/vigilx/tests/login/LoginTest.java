package com.vigilx.tests.login;

import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.ForgotPasswordPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.utils.LoggerUtils;

public class LoginTest extends BaseTest {

    private static final Logger logger =
            LoggerUtils.getLogger(LoginTest.class);

    @Test(priority = 1, description = "Verify Login Page Loads Successfully")
    public void verifyLoginPageLoaded() {

        logger.info("Verifying Login Page");

        LoginPage loginPage = new LoginPage(page);

        Assert.assertTrue(loginPage.isUsernameDisplayed());
        Assert.assertTrue(loginPage.isPasswordDisplayed());
        Assert.assertTrue(loginPage.isLoginButtonDisplayed());

        logger.info("Login Page Loaded Successfully");
    }

    @Test(priority = 1, description = "Verify Valid Login")
public void verifyValidLogin() {

    logger.info("Executing Valid Login Test");

    LoginPage loginPage = new LoginPage(page);

    DashboardPage dashboardPage =
            loginPage.login(
                    ConfigReader.get("username"),
                    ConfigReader.get("password"));

    Assert.assertTrue(
            dashboardPage.isDashboardLoaded(),
            "Dashboard page should be displayed after successful login.");

    logger.info("Valid Login Completed Successfully");
}
@Test(priority = 3, description = "Verify Invalid Username")
public void verifyInvalidUsername() {

    logger.info("Executing Invalid Username Test");

    LoginPage loginPage = new LoginPage(page);

    loginPage.attemptLogin(
            ConfigReader.get("invalidUsername"),
            ConfigReader.get("password"));

    Assert.assertTrue(
            loginPage.isLoginErrorDisplayed(),
            "Login error message should be displayed.");

    logger.info("Invalid Username Validation Completed");
}

  @Test(priority = 4, description = "Verify Invalid Password")
public void verifyInvalidPassword() {

    logger.info("Executing Invalid Password Test");

    LoginPage loginPage = new LoginPage(page);

    loginPage.attemptLogin(
            ConfigReader.get("username"),
            ConfigReader.get("invalidPassword"));

    Assert.assertTrue(
            loginPage.isLoginErrorDisplayed(),
            "Login error message should be displayed.");

    logger.info("Invalid Password Validation Completed");
}

    @Test(priority = 5, description = "Verify Empty Credentials")
    public void verifyEmptyCredentials() {

        logger.info("Executing Empty Credential Test");

        LoginPage loginPage = new LoginPage(page);

        loginPage.attemptLogin("", "");

        Assert.assertTrue( loginPage.isUsernameRequiredMessageDisplayed(), "Username required message should be displayed."); 
        Assert.assertTrue( loginPage.isPasswordRequiredMessageDisplayed(), "Password required message should be displayed."); Assert.assertEquals( loginPage.getUsernameRequiredMessage(), "Email is required"); 
        Assert.assertEquals( loginPage.getPasswordRequiredMessage(), "Password field is empty");
        

        logger.info("Empty Credential Validation Completed");
    }

    @Test(priority = 6, enabled = false, description = "Verify Password Visibility Toggle")
    public void verifyPasswordToggle() {

        logger.info("Executing Password Toggle Test");

        LoginPage loginPage = new LoginPage(page);
        loginPage.enterPassword("Test@123");
        loginPage.togglePassword();

        Assert.assertTrue(loginPage.isPasswordDisplayed());

        logger.info("Password Toggle Verified");
    }

   @Test(priority = 7, description = "Verify Forgot Password Navigation")
public void verifyForgotPassword() {

    logger.info("Executing Forgot Password Test");

    LoginPage loginPage = new LoginPage(page);

    ForgotPasswordPage forgotPasswordPage =
            loginPage.clickForgotPassword();

    Assert.assertTrue(
            forgotPasswordPage.isSendVerificationButtonDisplayed(),
            "Forgot Password page should be displayed.");

    logger.info("Forgot Password Navigation Verified");
}

}
