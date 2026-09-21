package com.vigilx.tests;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.ApplicationSettingsPage;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.InfraPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.NotificationRulesPage;

/** Standalone run: mvn test -Dtest=NotificationRulesTest (login -> Application Settings -> Device -> Notification Rules). */
public class NotificationRulesTest extends BaseTest {

    @Test
    public void runNotificationRules() {
        DashboardPage dashboard = new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
        Assert.assertTrue(dashboard.isDashboardLoaded(), "Dashboard should load after login.");

        InfraPage infra = new InfraPage(page);
        Assert.assertTrue(infra.open(), "Settings should open.");
        ApplicationSettingsPage applicationSettings = infra.openApplicationSettings();
        Assert.assertTrue(applicationSettings.openDevice(), "Application Settings -> Device should open.");

        Assert.assertTrue(new NotificationRulesPage(page).runNotificationRulesFlow(),
                "Notification Rules flow should pass.");
    }
}
