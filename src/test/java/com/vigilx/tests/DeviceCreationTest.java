package com.vigilx.tests;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.DeviceDetailsValidation;
import com.vigilx.pages.LoginPage;

/**
 * Standalone run of Create Device -> open that same device -> walk its existing tab-validation
 * flow (Details, Streams, Recordings, VA Settings, Health, Audit Logs, Controls) - the same
 * targeting mechanism (device.details.device.text) SoakHealthCheckRunner now sets after a
 * successful onboarding, exercised here in isolation: mvn test -Dtest=DeviceCreationTest
 */
public class DeviceCreationTest extends BaseTest {

    @Test
    public void runDeviceCreationThenTabValidation() {
        DashboardPage dashboard = new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
        Assert.assertTrue(dashboard.isDashboardLoaded(), "Dashboard should load after login.");

        String appUrl = ConfigReader.get("base.url").replace("/onboarding", "");
        DeviceDetailsValidation deviceCreation = new DeviceDetailsValidation(page);
        boolean created = deviceCreation.createDevice(appUrl);
        System.out.println("[DEVICE CREATION TEST] Create Device: " + (created ? "PASS" : "FAIL"));
        Assert.assertTrue(created, "Create Device should pass.");
        Assert.assertFalse(deviceCreation.lastCreatedDeviceName().isBlank(),
                "A unique device name should have been captured.");

        // Same knob SoakHealthCheckRunner sets: DeviceDetailsValidation.open() (unmodified) already
        // reads device.details.device.text to target one specific row instead of the first Online one.
        System.setProperty("device.details.device.text", deviceCreation.lastCreatedDeviceName());
        System.out.println("[DEVICE CREATION TEST] Targeting device: " + deviceCreation.lastCreatedDeviceName());

        boolean opened = deviceCreation.open(appUrl);
        System.out.println("[DEVICE CREATION TEST] Open newly onboarded device: " + (opened ? "PASS" : "FAIL"));
        Assert.assertTrue(opened, "The newly onboarded device should open.");

        boolean allTabsPassed = true;
        for (String section : DeviceDetailsValidation.FLOW) {
            boolean ok = deviceCreation.validateSection(section);
            System.out.println("[DEVICE CREATION TEST] Section '" + section + "': " + (ok ? "PASS" : "FAIL"));
            allTabsPassed &= ok;
        }
        System.out.println("[DEVICE CREATION TEST] All sections passed: " + allTabsPassed);

        boolean decommissioned = deviceCreation.decommissionDevice(appUrl, deviceCreation.lastCreatedDeviceName());
        System.out.println("[DEVICE CREATION TEST] Decommission: " + (decommissioned ? "PASS" : "FAIL"));
        Assert.assertTrue(decommissioned, "Decommissioning the newly onboarded device should pass.");
    }
}
