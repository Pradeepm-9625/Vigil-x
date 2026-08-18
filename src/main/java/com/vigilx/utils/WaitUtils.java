package com.vigilx.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class WaitUtils {

    private WaitUtils() {
    }

    public static void waitForVisible(Locator locator) {
        locator.waitFor();
    }

    public static void waitForPageLoad(Page page) {
        page.waitForLoadState();
    }

}