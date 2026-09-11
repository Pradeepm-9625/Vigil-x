package com.vigilx.pages;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Infra Management navigation: opens Settings ("Open settings") when it is not
 * already open, then "Infra Management Infra", then hands off to {@link ApplicationSettingsPage}
 * for "Application Settings" and everything under it.
 *
 * <p>Locators are kept here (and in {@link ApplicationSettingsPage}) rather than inline in a
 * validation class, per the existing page-object pattern the rest of {@code com.vigilx.pages}
 * follows - each is reusable by any future test that needs this same navigation.
 *
 * <p>Contract: never throws into the caller. Every step is logged and, on failure, returns
 * {@code false} (or - for {@link #openApplicationSettings()} - hands back an
 * {@link ApplicationSettingsPage} anyway, so the caller's own subsequent waits/log lines are what
 * surface the problem, exactly like every other soak-safe page in this package).
 */
public class InfraPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public InfraPage(Page page) {
        super(page);
    }

    /**
     * Reaches "Infra Management": clicks "Open settings" <em>only</em> when the Infra Management
     * link is not already on screen, so a caller that is already inside Settings (e.g. right after
     * {@link ApplicationHealthPage#validateSettings()}) never has the panel toggled shut from
     * under it - which would also break the Users &amp; Roles / Organisation checks that navigate
     * within that same open panel afterwards.
     */
    public boolean open() {
        Locator infraManagement = infraManagementLink();

        // Only click "Open settings" when Settings is not already open. The License check runs
        // just before this and leaves the panel open (its FAIL is a cosmetic heading check, not
        // the panel); "Open settings" is a toggle, so clicking it again there would close the
        // panel and break the Users & Roles / Organisation checks that navigate within it next.
        boolean settingsAlreadyOpen = SoakUiUtils.isVisibleQuietly(infraManagement.first())
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
                System.err.println("[INFRA] 'Open settings' could not be clicked: "
                        + SoakUiUtils.firstLine(exception.getMessage()));
                // Not fatal on its own - the Infra Management link below is the real signal.
            }
        }

        if (!SoakUiUtils.waitVisible(infraManagement, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[INFRA] 'Infra Management' navigation link not found.");
            return false;
        }
        try {
            infraManagement.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            System.out.println("[INFRA] Infra Management opened.");
            return true;
        } catch (Exception exception) {
            System.err.println("[INFRA] 'Infra Management' navigation link could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    /** Clicks "Application Settings" and hands off to its page object. */
    public ApplicationSettingsPage openApplicationSettings() {
        Locator link = page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Application Settings").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(link, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[INFRA] 'Application Settings' link not found.");
            return new ApplicationSettingsPage(page);
        }
        try {
            link.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            System.out.println("[INFRA] Application Settings opened.");
        } catch (Exception exception) {
            System.err.println("[INFRA] 'Application Settings' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
        return new ApplicationSettingsPage(page);
    }

    /**
     * The "Infra Management" navigation entry. The recording named it "Infra Management Infra"
     * (a group label + its "Infra" link concatenated); this matches any link whose accessible
     * name contains "infra", then falls back to the same as plain nav text, so it survives a
     * build where the accessible name is shaped differently.
     */
    private Locator infraManagementLink() {
        return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("infra\\s*management|infra", Pattern.CASE_INSENSITIVE)))
                .or(page.getByText(Pattern.compile("infra\\s*management", Pattern.CASE_INSENSITIVE)))
                .first();
    }
}
