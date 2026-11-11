package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.UsersRolesPage;

/**
 * Throwaway probe: opens Add User and calls the real {@link UsersRolesPage#selectGroup(String)}
 * directly, to confirm its fallback to the app's plain ".vxdd__menu .vxdd__item" buttons (no
 * listbox/option/menuitem role at all - confirmed live in an earlier run of this same probe)
 * actually selects a real group now. Never touches the rest of the soak flow.
 */
public final class GroupDropdownProbe {
    private GroupDropdownProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            String baseUrl = ConfigReader.get("base.url");
            page.navigate(baseUrl);
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            System.out.println("PROBE: login done");

            UsersRolesPage usersRoles = new UsersRolesPage(page);
            System.out.println("PROBE: navigateToUsersRoles = " + usersRoles.navigateToUsersRoles());
            System.out.println("PROBE: navigateToUsers = " + usersRoles.navigateToUsers());
            System.out.println("PROBE: clickAddUser = " + usersRoles.clickAddUser());

            boolean groupSelected = usersRoles.selectGroup("Test");
            System.out.println("PROBE: selectGroup(\"Test\") = " + groupSelected);

            Locator groupButtonStillShowingPlaceholder = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Select Group").setExact(true)).first();
            System.out.println("PROBE: 'Select Group' placeholder still visible (should be false) = "
                    + groupButtonStillShowingPlaceholder.isVisible());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            page.waitForTimeout(3000);
            PlaywrightFactory.closeBrowser();
        }
    }
}
