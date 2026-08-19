package com.vigilx.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class WaitUtils {

    public static final long PAGE_NAVIGATION_WAIT_MS = 5000;

    private WaitUtils() {
    }

    public static void waitForVisible(Locator locator) {
        locator.waitFor();
    }

    public static void waitForPageLoad(Page page) {
        page.waitForLoadState();
    }

    public static void waitAfterPageNavigation(Page page) {
        page.waitForTimeout(PAGE_NAVIGATION_WAIT_MS);
    }

}