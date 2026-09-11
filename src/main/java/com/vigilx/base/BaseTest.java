package com.vigilx.base;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.utils.WaitUtils;

/**
 * BaseTest
 *
 * Parent class for all TestNG test classes.
 * Responsible for:
 * 1. Launching the browser
 * 2. Opening the application URL
 * 3. Closing the browser after execution
 */
public class BaseTest {

    protected Page page;

    @BeforeMethod(alwaysRun = true)
    public void setUp() {

        // Launch browser and create a new page
        page = PlaywrightFactory.initializeBrowser();

        // Navigate to application
        page.navigate(ConfigReader.get("base.url"));
        WaitUtils.waitAfterPageNavigation(page);

    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {

        // Close browser
        PlaywrightFactory.closeBrowser();

    }

}