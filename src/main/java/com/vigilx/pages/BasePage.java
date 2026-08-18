package com.vigilx.pages;

import com.microsoft.playwright.Page;
import com.vigilx.utils.ElementActions;

public class BasePage {

    protected final Page page;
    protected final ElementActions actions;

    public BasePage(Page page) {

        this.page = page;
        this.actions = new ElementActions(page);

    }

}