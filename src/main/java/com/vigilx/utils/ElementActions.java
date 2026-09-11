package com.vigilx.utils;

import java.nio.file.Path;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

public class ElementActions {

    private final Page page;

    public ElementActions(Page page) {
        this.page = page;
    }

    // =========================
    // Click Actions
    // =========================

    public void click(Locator locator) {
        locator.waitFor();
        locator.click();
    }

    public void doubleClick(Locator locator) {
        locator.waitFor();
        locator.dblclick();
    }

    public void rightClick(Locator locator) {
        locator.waitFor();
        locator.click(new Locator.ClickOptions().setButton(com.microsoft.playwright.options.MouseButton.RIGHT));
    }

    // =========================
    // Text Actions
    // =========================

    public void type(Locator locator, String value) {
        locator.waitFor();
        locator.fill(value);
    }

    public void clear(Locator locator) {
        locator.waitFor();
        locator.clear();
    }

    public void pressKey(Locator locator, String key) {
        locator.waitFor();
        locator.press(key);
    }

    // =========================
    // Checkbox
    // =========================

    public void check(Locator locator) {
        locator.waitFor();
        locator.check();
    }

    public void uncheck(Locator locator) {
        locator.waitFor();
        locator.uncheck();
    }

    // =========================
    // Mouse Actions
    // =========================

    public void hover(Locator locator) {
        locator.waitFor();
        locator.hover();
    }

    public void scrollIntoView(Locator locator) {
        locator.scrollIntoViewIfNeeded();
    }

    // =========================
    // File Upload
    // =========================

    public void uploadFile(Locator locator, String filePath) {
        locator.setInputFiles(Path.of(filePath));
    }

    // =========================
    // Drag and Drop
    // =========================

    public void dragAndDrop(Locator source, Locator target) {
        source.dragTo(target);
    }

    // =========================
    // Waits
    // =========================

    public void waitForVisible(Locator locator) {
        locator.waitFor();
    }

    public void waitForPageLoad() {
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    // =========================
    // Read Values
    // =========================

    public String getText(Locator locator) {
        locator.waitFor();
        return locator.textContent();
    }

    public String getValue(Locator locator) {
        locator.waitFor();
        return locator.inputValue();
    }

    public boolean isVisible(Locator locator) {
        return locator.isVisible();
    }

    public boolean isEnabled(Locator locator) {
        return locator.isEnabled();
    }

    public boolean isChecked(Locator locator) {
        return locator.isChecked();
    }

    // =========================
    // Browser
    // =========================

    public void refresh() {
        page.reload();
    }

    public void goBack() {
        page.goBack();
    }

    public void goForward() {
        page.goForward();
    }

    public String getTitle() {
        return page.title();
    }

    public String getCurrentUrl() {
        return page.url();
    }
}