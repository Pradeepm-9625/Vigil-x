package com.vigilx.pages;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Dashboard Page Object
 *
 * Contains dashboard related actions and validations.
 *
 * @author Pradeep
 */
public class DashboardPage extends BasePage {

    //====================================================
    // Locators
    //====================================================

    /** Header profile button: two initials followed by the user's name, e.g. "PM Pradeep Mm Admin". */
    private static final Pattern PROFILE_BY_INITIALS = Pattern.compile("^[A-Z]{2}\\s+\\S+");

    /** Fallback wording used by other deployments of the same header. */
    private static final Pattern PROFILE_BY_KEYWORD =
            Pattern.compile("profile|account|my\\s+account", Pattern.CASE_INSENSITIVE);

    private static final Pattern LOGOUT =
            Pattern.compile("log\\s*out|sign\\s*out", Pattern.CASE_INSENSITIVE);

    private final Locator profileIcon;
    private final Locator logoutButton;

    //====================================================
    // Constructor
    //====================================================

    public DashboardPage(Page page) {

        super(page);

        // The header profile button is labelled with the logged-in user's initials and name
        // ("PM Pradeep Mm Admin", "PQ Pradeep Qa Admin", ...), so it is matched by shape rather than
        // by one hard-coded user. Fallbacks cover deployments that label it differently.
        profileIcon = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(PROFILE_BY_INITIALS))
                .or(page.getByRole(
                        AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(PROFILE_BY_KEYWORD)))
                .or(page.locator(
                        "[class*='avatar' i], [class*='profile' i], [data-testid*='profile' i],"
                                + " [aria-label*='account' i], [aria-label*='profile' i]"))
                .first();

        // Log Out is a menu item that only exists once the profile menu is open.
        logoutButton = page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName(LOGOUT))
                .or(page.getByRole(AriaRole.MENUITEMRADIO, new Page.GetByRoleOptions().setName(LOGOUT)))
                .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(LOGOUT)))
                .or(page.getByText(LOGOUT))
                .first();

    }

    //====================================================
    // Validation
    //====================================================

    /**
     * Verify Dashboard Loaded
     */
    public boolean isDashboardLoaded() {
        return page.url().matches(".*/dashboard(?:[/?#].*)?$");

    }

    /**
     * Verify Profile Icon
     */
    public boolean isProfileVisible() {

        return actions.isVisible(profileIcon);

    }

    //====================================================
    // Actions
    //====================================================

    /**
     * Click Profile
     */
    public DashboardPage clickProfile() {

        actions.click(profileIcon);

        return this;

    }

    /**
     * Logout
     */
    public LoginPage logout() {

        clickProfile();
        logoutButton.waitFor(new Locator.WaitForOptions().setTimeout(15000));
        actions.click(logoutButton);

        page.waitForURL("**/onboarding");
        waitAfterPageNavigation();

        return new LoginPage(page);

    }

    /**
     * Logs out without throwing, retrying from the dashboard if the current page has no header.
     *
     * <p>The soak run reaches logout from the Archive page, where the profile control is not always
     * present; the retry navigates somewhere it reliably is.
     *
     * @param appUrl application root used for the retry, may be {@code null} to skip it
     * @return true when the session was ended
     */
    public boolean logoutSafely(String appUrl) {

        if (attemptLogout()) {
            return true;
        }

        if (appUrl != null && !appUrl.isBlank()) {
            try {
                System.out.println("[LOGOUT] Profile control not found here; retrying from the dashboard.");
                navigateTo(appUrl + "/dashboard");
                return attemptLogout();
            } catch (Exception exception) {
                System.err.println("[LOGOUT] Retry from the dashboard failed: " + exception.getMessage());
            }
        }
        return false;
    }

    /** One logout attempt on the current page: open the profile menu, click Log Out, verify. */
    private boolean attemptLogout() {
        try {
            if (profileIcon.count() == 0 || !profileIcon.isVisible()) {
                return false;
            }

            profileIcon.click(new Locator.ClickOptions().setTimeout(10000));

            logoutButton.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(10000));
            logoutButton.click(new Locator.ClickOptions().setTimeout(10000));

            return isLoggedOut();

        } catch (Exception exception) {
            System.err.println("[LOGOUT] Attempt failed: " + exception.getMessage());
            return false;
        }
    }

    /** True once the app has returned to onboarding/login. */
    private boolean isLoggedOut() {
        try {
            page.waitForURL("**/onboarding**", new Page.WaitForURLOptions().setTimeout(15000));
            waitAfterPageNavigation();
            System.out.println("[LOGOUT] Session ended; back at onboarding.");
            return true;
        } catch (Exception exception) {
            // Some builds land on a different login route; fall back to detecting the login form.
            try {
                boolean atLogin = page.getByRole(AriaRole.TEXTBOX,
                        new Page.GetByRoleOptions().setName("Email")).isVisible(
                                new Locator.IsVisibleOptions().setTimeout(5000));
                if (atLogin) {
                    System.out.println("[LOGOUT] Session ended; login form is shown.");
                }
                return atLogin;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /**
     * Get Dashboard Title
     */
    public String getTitle() {

        return actions.getTitle();

    }

    /**
     * Get Current URL
     */
    public String getCurrentUrl() {

        return actions.getCurrentUrl();

    }

}
