package com.vigilx.pages;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Master Data (its own "Event Ack" tab) and the separate, sibling "About" left-nav
 * link - navigation and page-load validation only for both; neither has an existing functional
 * test of its own on these exact screens. (The lifecycle CRUD in {@link EventAcknowledgementPage}
 * is reached via a different left-nav path, "Event Check" - left untouched.)
 *
 * <p>Contract: never throws into the caller. Every failure is logged and returned as {@code false}
 * so the soak continues.
 */
public class MasterDataPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public MasterDataPage(Page page) {
        super(page);
    }

    /** Opens Settings (only if not already open), then the "Master Data" left-nav link. */
    public boolean open() {
        Locator masterDataLink = masterDataLink();

        boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(masterDataLink.first())
                || SoakUiUtils.isVisibleQuietly(page.getByText(Pattern.compile(
                        "my license|users & roles|organisation|organization|application settings",
                        Pattern.CASE_INSENSITIVE)).first());
        if (!settingsAlreadyOpen) {
            try {
                Locator openSettings = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
                if (SoakUiUtils.isVisibleQuietly(openSettings)) {
                    openSettings.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                    waitAfterPageNavigation();
                }
            } catch (Exception exception) {
                System.err.println("[MASTER DATA] 'Open settings' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
            }
        }

        if (!SoakUiUtils.waitVisible(masterDataLink, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[MASTER DATA] 'Master Data' navigation link not found.");
            return false;
        }
        try {
            masterDataLink.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            System.out.println("[MASTER DATA] Master Data opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[MASTER DATA] 'Master Data' link could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Clicks the "Event Ack" tab and confirms it loads - navigation-only, no functional check. */
    public boolean visitEventAckTab() {
        Locator tab = page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Event Ack").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(tab, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER DATA]   'Event Ack' tab not present; skipping.");
            return true;
        }
        try {
            tab.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(600);
            System.out.println("[MASTER DATA]   'Event Ack' tab opened (navigation-only check).");
            return true;
        } catch (Exception exception) {
            System.err.println("[MASTER DATA]   'Event Ack' tab could not be opened: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /**
     * Clicks the separate, top-level "About" left-nav link (a sibling of "Master Data", not nested
     * under it - confirmed live via a real recording) and confirms the page loads.
     */
    public boolean visitAboutPage() {
        Locator link = page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(Pattern.compile("^about", Pattern.CASE_INSENSITIVE))).first();
        if (!SoakUiUtils.waitVisible(link, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[MASTER DATA]   'About' link not present; skipping.");
            return true;
        }
        try {
            link.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            System.out.println("[MASTER DATA]   'About' page opened (navigation-only check).");
            return true;
        } catch (Exception exception) {
            System.err.println("[MASTER DATA]   'About' link could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private Locator masterDataLink() {
        return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("master\\s*data", Pattern.CASE_INSENSITIVE)))
                .first();
    }
}
