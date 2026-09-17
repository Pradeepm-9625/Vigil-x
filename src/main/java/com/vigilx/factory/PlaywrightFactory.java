package com.vigilx.factory;

import java.util.Collections;
import java.util.List;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

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

        // Headed runs: launch the actual OS window maximized (--start-maximized) instead of a
        // fixed 1920x1080 content viewport that may not match - or may be smaller/positioned
        // oddly against - the real screen, which is what was cutting off the right-hand side of
        // the page during a visible run. Chromium-based browsers (chromium/chrome/edge) support
        // this launch arg directly; Firefox/WebKit do not take Chromium args, so they keep their
        // existing behavior. Headless runs are completely unaffected either way - there is no real
        // screen to maximize into, so they keep the same fixed 1920x1080 viewport as before.
        List<String> maximizedArgs = headless ? Collections.emptyList() : List.of("--start-maximized");

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
                                .setSlowMo((double) slowMo)
                                .setArgs(maximizedArgs));

                break;

            case "chrome":

                browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions()
                                .setChannel("chrome")
                                .setHeadless(headless)
                                .setSlowMo((double) slowMo)
                                .setArgs(maximizedArgs));

                break;

            case "chromium":

            default:

                browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(headless)
                                .setSlowMo((double) slowMo)
                                .setArgs(maximizedArgs));

        }

        // Headed: pass a null viewport so the page renders at the real (now maximized) window
        // size instead of being forced back down to a fixed 1920x1080 content area - this is what
        // actually makes the page fill the visible screen. Headless: unchanged, fixed 1920x1080.
        Browser.NewContextOptions contextOptions = headless
                ? new Browser.NewContextOptions().setViewportSize(1920, 1080)
                : new Browser.NewContextOptions().setViewportSize(null);
        browserContext = browser.newContext(contextOptions);

        // Attached before the first page exists, so no request can be missed and popups/new pages
        // opened later are covered too.
        ApiMonitor.attach(browserContext);

        page = browserContext.newPage();

        page.setDefaultTimeout(
                ConfigReader.getInt("timeout"));

        // No page-level attach here: the context listener above already covers this page, and
        // registering both would count every response twice.

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

        // Written before teardown so the consolidated report survives a failing close().
        ApiMonitor.writeReportQuietly();
        // No-op unless api.inventory.enabled=true (off by default); same "survive a failing
        // close()" reasoning as the failure report above, for the separate opt-in API inventory.
        ApiMonitor.writeInventoryReportQuietly();

        // Idempotent: the soak run closes the browser as soon as logout ends, and its finally
        // block calls this again. Every handle is dropped so the second call is a no-op.
        if (browserContext != null) {
            browserContext.close();
            browserContext = null;
        }

        if (browser != null) {
            browser.close();
            browser = null;
        }

        if (playwright != null) {
            playwright.close();
            playwright = null;
        }

        page = null;

    }

}
