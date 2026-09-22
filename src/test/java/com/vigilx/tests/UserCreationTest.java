package com.vigilx.tests;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.UserData;
import com.vigilx.pages.UsersRolesPage;

/**
 * Standalone run of the "Add User" -> full user lifecycle flow, same shape as SoakHealthCheckRunner:
 * mvn test -Dtest=UserCreationTest
 */
public class UserCreationTest extends BaseTest {

    @Test
    public void runUserCreationFlow() {
        DashboardPage dashboard = new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
        Assert.assertTrue(dashboard.isDashboardLoaded(), "Dashboard should load after login.");

        UsersRolesPage usersRoles = new UsersRolesPage(page);
        // Only the number after "+" in the configured email is replaced with a per-run unique value
        // (6 digits of epoch seconds); an email without a "+tag" is used unchanged.
        String userEmail = ConfigReader.getOrDefault("user.creation.email", "pradeep.m+50@solutionchamps.com")
                .replaceFirst("\\+[^@]*@", java.util.regex.Matcher.quoteReplacement("+"
                        + String.format("%06d", (System.currentTimeMillis() / 1000) % 1_000_000) + "@"));
        UserData newUser = new UserData(
                ConfigReader.getOrDefault("user.creation.first.name", "Pradeep"),
                ConfigReader.getOrDefault("user.creation.last.name", "Testing"),
                userEmail,
                ConfigReader.getOrDefault("user.creation.mobile", "99999999999"),
                ConfigReader.getOrDefault("user.creation.role", "Quality Check"),
                ConfigReader.getOrDefault("user.creation.group", "Test"),
                ConfigReader.getOrDefault("user.creation.profile.picture", "src/test/resources/Logo.jpg"));
        UserData updatedUser = new UserData(
                newUser.firstName() + ConfigReader.getOrDefault("user.update.first.name.suffix", "update"),
                newUser.lastName(), newUser.email(), newUser.mobileNumber(), newUser.role(),
                newUser.group(), newUser.profilePicturePath());

        boolean created = usersRoles.createUserEndToEnd(newUser);
        System.out.println("[USER CREATION TEST] Create User: " + (created ? "PASS" : "FAIL"));

        boolean lifecycleOk = usersRoles.runUserLifecycle(newUser, updatedUser);
        System.out.println("[USER CREATION TEST] User Lifecycle: " + (lifecycleOk ? "PASS" : "FAIL"));

        Assert.assertTrue(created, "Create User should pass.");
        Assert.assertTrue(lifecycleOk, "User Lifecycle should pass.");
    }
}
