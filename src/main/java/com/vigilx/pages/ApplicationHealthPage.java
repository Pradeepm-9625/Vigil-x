package com.vigilx.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.vigilx.config.ConfigReader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only navigation and visibility checks for the pages captured in the supplied recording. */
public class ApplicationHealthPage extends BasePage {
    public ApplicationHealthPage(Page page) { super(page); }

    /**
     * Opens Project Hierarchy and waits for the tree itself.
     *
     * <p>The check used to wait for the transient "Loading..." label, which is already gone once the
     * tree has rendered - so a healthy page reported FAIL. The node search box and the Add Devices
     * action belong to the loaded page and stay on screen.
     */
    public boolean validateProjectHierarchy() {
        return openLinkAndCheck("Project Hierarchy",
                page.getByPlaceholder("Search Node, Site, Devices")
                        .or(page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Add Devices").setExact(false))));
    }
    public boolean validateDevices() { return openLinkAndCheck("Devices", "Online"); }

    public boolean validateUsersAndRoles() { return openTextNavigationAndCheck("Users & Roles", "User Management"); }
    public boolean validateOrganisation() { return openTextNavigationAndCheck("Organisation", "Organization Information"); }

    public boolean validateSettings() {
        Locator settings = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Open settings"));
        settings.click();
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("My license").setExact(true)).isVisible();
    }

    /**
     * When Device Creation has just onboarded a device (device.details.device.text set), its own
     * configuration page is already open - reused here as-is, with no navigation back to the
     * Devices list and no re-selecting any row (which could land on a different device). Otherwise
     * falls back to the original behaviour: open Devices, click the first Online row.
     */
    public boolean validateDeviceTabs() {
        boolean targetingCreatedDevice =
                !ConfigReader.getOrDefault("device.details.device.text", "").isBlank()
                        && page.locator(".device-config-v1-page__body").isVisible();
        if (!targetingCreatedDevice) {
            if (!validateDevices()) return false;
            page.getByText("Online", new Page.GetByTextOptions().setExact(true)).first().click();
        }
        for (String tab : new String[] {"Streams", "Details", "Recordings", "VA Settings", "Health"}) {
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName(tab).setExact(true)).click();
        }
        // Deliberately does not click Add AI Models, Submit, Manual Health Check, or Run All.
        return page.locator(".device-config-v1-page__body").isVisible();
    }

    /** Runs after Devices and Device Tabs: opens Alerts V1 and validates the Video Alert playback. */
    public boolean validateAlerts(String baseUrl, Path screenshotPath) {
        return new alert(page).validateAlerts(baseUrl, screenshotPath);
    }

    public boolean validateLiveView(String baseUrl) {
        return new Live_view(page).validateLiveView(baseUrl);
    }

   
        public boolean validateMap(String baseUrl) {
                return new Map(page).validateMap(baseUrl);
        }

        /** Robust map/marker/stream validation. Additional to {@link #validateMap}, which is unchanged. */
        public boolean validateMapCameras(String baseUrl) {
                return new MapValidation(page).validateMap(baseUrl);
        }

        private boolean validateCameraStream(Locator cameraCell, int cameraNumber, Path screenshotDir) {
                try {
                        Locator media = cameraCell.locator("video, canvas, [class*='video' i], [class*='player' i]").first();
                        if (media.count() == 0) {
                                captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-no-media");
                                return false;
                        }

                        media.waitFor(new Locator.WaitForOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(15000));

                        String tagName = media.evaluate("element => element.tagName.toLowerCase()").toString();
                        if ("video".equals(tagName)) {
                                String source = media.evaluate("element => element.currentSrc || element.src || ''").toString();
                                if (source.isBlank()) {
                                        captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-no-source");
                                        return false;
                                }

                                media.evaluate("element => { element.muted = true; element.play(); }");
                                page.waitForTimeout(1000);
                                boolean playing = (Boolean) media.evaluate(
                                                "element => !element.paused && !element.ended && element.currentTime > 0");
                                if (!playing) {
                                        captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-not-playing");
                                        return false;
                                }
                        }

                        return true;
                } catch (Exception exception) {
                        captureLiveViewScreenshot(screenshotDir, "camera-" + cameraNumber + "-exception");
                        return false;
                }
        }

        private void captureLiveViewScreenshot(Path screenshotDir, String name) {
                try {
                        Files.createDirectories(screenshotDir);
                        Path screenshotPath = screenshotDir.resolve(name + ".png");
                        page.screenshot(new Page.ScreenshotOptions()
                                        .setPath(screenshotPath)
                                        .setFullPage(true));
                        System.out.println("[SCREENSHOT] Live View failure saved to " + screenshotPath);
                } catch (Exception exception) {
                        System.err.println("[SCREENSHOT FAILURE] Could not save Live View screenshot: "
                                        + exception.getMessage());
                }
        }

    /** Robust archive/add-camera/playback validation. Additional to {@link #validateArchive}, which is unchanged. */
    public boolean validateArchiveCameras(String baseUrl) {
        return new ArchiveValidation(page).validateArchive(baseUrl);
    }

    /**
     * Opens Archive and waits for the Playback workspace.
     *
     * <p>This used to assert that a camera literally named "Camera 7008" was on screen, which no
     * environment except the one the flow was recorded on can satisfy. The workspace itself is what
     * this read-only check is actually about; per-camera playback is covered by
     * {@link #validateArchiveCameras(String)}.
     */
    public boolean validateArchive(String baseUrl) {
        navigateTo(baseUrl + "/live-views/archive");
        return isPlaybackWorkspaceReady();
    }

    /** True once the Archive/Playback workspace has rendered, whatever cameras it holds. */
    private boolean isPlaybackWorkspaceReady() {
        try {
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Playback").setExact(true))
                    .or(page.getByPlaceholder("Search Node, Site, Devices"))
                    .first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(20000));
            return true;
        } catch (Exception exception) {
            System.err.println("[ARCHIVE] Playback workspace did not render: " + exception.getMessage());
            return false;
        }
    }

    public boolean addCameraToPlayback(String baseUrl, String cameraName) {
        navigateTo(baseUrl + "/live-views/archive");

        // Keep the existing archive behavior intact: first ensure the page is actually loaded.
        if (!isPlaybackWorkspaceReady()) {
            return false;
        }

        // If the target camera is already visible, do nothing and keep validation stable.
        Locator existingCamera = page.getByText(cameraName, new Page.GetByTextOptions().setExact(false)).first();
        if (existingCamera.count() > 0 && existingCamera.isVisible()) {
            return true;
        }

        // Try the add-camera flow used by the archive UI.
        Locator addCameraButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Add camera").setExact(false)).first();
        if (addCameraButton.count() > 0) {
            addCameraButton.click();
        }

        Locator expandTree = page.locator("[id*='mui-tree-view'], .MuiTreeItem-content, .ph-v1-dynamic-tree-node__expand-circle").first();
        if (expandTree.count() > 0) {
            try {
                expandTree.click();
            } catch (Exception ignored) {
                // Ignore if the tree is already expanded.
            }
        }

        Locator cameraCheckbox = page.getByRole(AriaRole.CHECKBOX).nth(3);
        if (cameraCheckbox.count() > 0) {
            try {
                cameraCheckbox.check();
            } catch (Exception ignored) {
                try {
                    cameraCheckbox.click();
                } catch (Exception ignoredAgain) {
                    // Suppress selector mismatch; this is treated as a non-fatal validation failure.
                }
            }
        }

        Locator saveChanges = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
        if (saveChanges.count() > 0) {
            try {
                saveChanges.click();
            } catch (Exception ignored) {
                // Save button may be disabled or not yet visible; still continue without breaking the flow.
            }
        }

        return page.getByText(cameraName, new Page.GetByTextOptions().setExact(false)).first().isVisible();
    }

    /** Opens a left-nav link, then waits for an element that only the loaded page owns. */
    private boolean openLinkAndCheck(String linkName, Locator expected) {
        try {
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(linkName).setExact(false))
                    .click(new Locator.ClickOptions().setTimeout(5000));
            waitAfterPageNavigation();
        } catch (Exception ignored) {
            return false;
        }
        try {
            expected.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(15000));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean openLinkAndCheck(String linkName, String expectedText) {
        try {
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(linkName).setExact(false))
                    .click(new Locator.ClickOptions().setTimeout(2000));
            waitAfterPageNavigation();
        } catch (Exception ignored) {
            return false;
        }
        try {
            return page.getByText(expectedText, new Page.GetByTextOptions().setExact(false)).first()
                    .isVisible(new Locator.IsVisibleOptions().setTimeout(2000));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean openTextNavigationAndCheck(String navigationText, String expectedText) {
        try {
            page.getByText(navigationText, new Page.GetByTextOptions().setExact(false)).first()
                    .click(new Locator.ClickOptions().setTimeout(2000));
            waitAfterPageNavigation();
        } catch (Exception ignored) {
            return false;
        }
        try {
            return page.getByText(expectedText, new Page.GetByTextOptions().setExact(false)).first()
                    .isVisible(new Locator.IsVisibleOptions().setTimeout(2000));
        } catch (Exception ignored) {
            return false;
        }
    }
}
