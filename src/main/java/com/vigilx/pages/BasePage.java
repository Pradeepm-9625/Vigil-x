package com.vigilx.pages;

import com.microsoft.playwright.Page;
import com.vigilx.utils.ElementActions;
import com.vigilx.utils.WaitUtils;

public class BasePage {

    protected final Page page;
    protected final ElementActions actions;

    public BasePage(Page page) {

        this.page = page;
        this.actions = new ElementActions(page);

    }

    protected void navigateTo(String url) {
        page.navigate(url);
        WaitUtils.waitAfterPageNavigation(page);
    }

    protected void waitAfterPageNavigation() {
        WaitUtils.waitAfterPageNavigation(page);
    }

}