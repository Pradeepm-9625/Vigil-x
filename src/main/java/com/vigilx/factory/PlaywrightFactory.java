package com.vigilx.factory;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.vigilx.config.ConfigReader;

public final class PlaywrightFactory {

    private static Playwright playwright;
    private static Browser browser;
    private static BrowserContext browserContext;
    private static Page page;

    private PlaywrightFactory() {
    }

    public static Page initializeBrowser() {

        String browserName = ConfigReader.get("browser");

        boolean headless =
                ConfigReader.getBoolean("headless");

        int slowMo =
                ConfigReader.getInt("slowmo");

        playwright = Playwright.create();

        switch (browserName.toLowerCase()) {

            case "firefox":

                browser = playwright.firefox().launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(headless)
                                .setSlowMo((double) slowMo));

                break;

            case "webkit":

                browser = playwright.webkit().launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(headless)
                                .setSlowMo((double) slowMo));

                break;

            case "edge":

                browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions()
                                .setChannel("msedge")
                                .setHeadless(headless)
                                .setSlowMo((double) slowMo));

                break;

            case "chromium":

            default:

                browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(headless)
                                .setSlowMo((double) slowMo));

        }

        browserContext = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(1920,1080));

        page = browserContext.newPage();

        page.setDefaultTimeout(
                ConfigReader.getInt("timeout"));

        return page;

    }

    public static Page getPage() {
        return page;
    }

    public static BrowserContext getContext() {
        return browserContext;
    }

    public static Browser getBrowser() {
        return browser;
    }

    public static void closeBrowser() {

        if(browserContext!=null)
            browserContext.close();

        if(browser!=null)
            browser.close();

        if(playwright!=null)
            playwright.close();

    }

}
