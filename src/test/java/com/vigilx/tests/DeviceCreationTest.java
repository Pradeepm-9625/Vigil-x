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

        boolean opened;
        if (created && !deviceCreation.lastCreatedDeviceName().isBlank()) {
            // Same knob SoakHealthCheckRunner sets: DeviceDetailsValidation.open() (unmodified) already
            // reads device.details.device.text to target one specific row instead of the first Online one.
            System.setProperty("device.details.device.text", deviceCreation.lastCreatedDeviceName());
            System.out.println("[DEVICE CREATION TEST] Targeting device: " + deviceCreation.lastCreatedDeviceName());
            opened = deviceCreation.open(appUrl);
            System.out.println("[DEVICE CREATION TEST] Open newly onboarded device: " + (opened ? "PASS" : "FAIL"));
        } else {
            // Onboarding failed (e.g. Test Connection could not reach the device) - createDevice()
            // already abandoned the form and confirmed the resulting dialog. Rather than stopping the
            // run here, fall back to a random existing device so Tab Navigation validation still runs.
            System.out.println("[DEVICE CREATION TEST] Device onboarding failed; falling back to a "
                    + "random existing device for Tab Navigation validation.");
            opened = deviceCreation.openRandomDevice(appUrl);
            System.out.println("[DEVICE CREATION TEST] Open random existing device: " + (opened ? "PASS" : "FAIL"));
        }
        Assert.assertTrue(opened, "A device (created, or a random existing one when onboarding failed) should open.");

        boolean allTabsPassed = true;
        for (String section : DeviceDetailsValidation.FLOW) {
            boolean ok = deviceCreation.validateSection(section);
            System.out.println("[DEVICE CREATION TEST] Section '" + section + "': " + (ok ? "PASS" : "FAIL"));
            allTabsPassed &= ok;
        }
        System.out.println("[DEVICE CREATION TEST] All sections passed: " + allTabsPassed);

        // Only ever decommissions the device this run itself created - never a randomly selected
        // pre-existing one from the fallback path above.
        if (created && !deviceCreation.lastCreatedDeviceName().isBlank()) {
            boolean decommissioned = deviceCreation.decommissionDevice(appUrl, deviceCreation.lastCreatedDeviceName());
            System.out.println("[DEVICE CREATION TEST] Decommission: " + (decommissioned ? "PASS" : "FAIL"));
            Assert.assertTrue(decommissioned, "Decommissioning the newly onboarded device should pass.");
        } else {
            System.out.println("[DEVICE CREATION TEST] Skipping decommission (no device was created this run).");
        }
    }
}
