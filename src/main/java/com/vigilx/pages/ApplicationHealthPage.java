package com.vigilx.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/** Read-only navigation and visibility checks for the pages captured in the supplied recording. */
public class ApplicationHealthPage extends BasePage {
    public ApplicationHealthPage(Page page) { super(page); }

    public boolean validateProjectHierarchy() { return openLinkAndCheck("Project Hierarchy", "Loading..."); }
    public boolean validateDevices() { return openLinkAndCheck("Devices", "Online"); }
    public boolean validateAlerts() {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Alerts").setExact(false)).click();

        Locator videoAlertButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Video Alert").setExact(false)).first();
        if (videoAlertButton.count() == 0) {
            return false;
        }

        try {
            videoAlertButton.click();
        } catch (Exception ignored) {
            return false;
        }

        Locator allAlertEntries = page.locator("[role='row'], .alerts-v1-page, .alerts-v1-list-item, .alert-card")
                .filter(new Locator.FilterOptions().setHasText("Video Alert"));

        int alertCount = allAlertEntries.count();
        if (alertCount == 0) {
            return false;
        }

        int maxAlerts = Math.min(5, alertCount);
        boolean anySuccess = false;

        for (int i = 0; i < maxAlerts; i++) {
            try {
                Locator currentAlert = allAlertEntries.nth(i);
                if (currentAlert.count() == 0) {
                    continue;
                }
                currentAlert.click();

                Locator previewButton = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Preview").setExact(false)).first();
                if (previewButton.count() > 0) {
                    previewButton.click();
                } else {
                    Locator labelPreview = page.getByLabel("Preview").first();
                    if (labelPreview.count() > 0) {
                        labelPreview.click();
                    }
                }

                Locator media = page.locator("video, canvas, [class*='player' i], [class*='video' i], [data-testid*='video' i], img[alt='Alert preview']").first();
                boolean previewVisible = false;
                try {
                    previewVisible = media.isVisible();
                } catch (Exception ignored) {
                    try {
                        previewVisible = page.getByAltText("Alert preview").first().isVisible();
                    } catch (Exception ignored2) {
                        previewVisible = false;
                    }
                }

                if (previewVisible) {
                    anySuccess = true;
                }

                Locator closePreview = page.getByLabel("Close slider popup").first();
                if (closePreview.count() > 0) {
                    closePreview.click();
                } else {
                    Locator closeDialog = page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Close dialog").setExact(false)).first();
                    if (closeDialog.count() > 0) {
                        closeDialog.click();
                    }
                }
            } catch (Exception ignored) {
                try {
                    Locator closePreview = page.getByLabel("Close slider popup").first();
                    if (closePreview.count() > 0) {
                        closePreview.click();
                    } else {
                        Locator closeDialog = page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Close dialog").setExact(false)).first();
                        if (closeDialog.count() > 0) {
                            closeDialog.click();
                        }
                    }
                } catch (Exception ignoredClose) {
                    // Continue to next alert when the video is unavailable or the preview cannot be opened.
                }
            }
        }

        return anySuccess;
    }


    public boolean validateUsersAndRoles() { return openTextNavigationAndCheck("Users & Roles", "User Management"); }
    public boolean validateOrganisation() { return openTextNavigationAndCheck("Organisation", "Organization Information"); }

    public boolean validateSettings() {
        Locator settings = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Open settings"));
        settings.click();
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("My license").setExact(true)).isVisible();
    }

    public boolean validateDeviceTabs() {
        if (!validateDevices()) return false;
        page.getByText("Online", new Page.GetByTextOptions().setExact(true)).first().click();
        for (String tab : new String[] {"Streams", "Details", "Recordings", "VA Settings", "Health"}) {
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName(tab).setExact(true)).click();
        }
        // Deliberately does not click Add AI Models, Submit, Manual Health Check, or Run All.
        return page.locator(".device-config-v1-page__body").isVisible();
    }

    public boolean validateLiveView(String baseUrl) {
        page.navigate(baseUrl + "/live-views/views/v1");
        return page.locator(".operator-panel__body").isVisible();
    }

    public boolean validateMap(String baseUrl) {
        page.navigate(baseUrl + "/live-views/maps");
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Device").setExact(true)).isVisible();
    }

    public boolean validateArchive(String baseUrl) {
        page.navigate(baseUrl + "/live-views/archive");
        return page.getByText("Camera 7008", new Page.GetByTextOptions().setExact(true)).isVisible();
    }

    public boolean addCameraToPlayback(String baseUrl, String cameraName) {
        page.navigate(baseUrl + "/live-views/archive");

        // Keep the existing archive behavior intact: first ensure the page is actually loaded.
        boolean pageReady = page.getByText("Camera 7008", new Page.GetByTextOptions().setExact(false)).first().isVisible()
                || page.getByText("vms smart city survilien", new Page.GetByTextOptions().setExact(false)).first().isVisible();
        if (!pageReady) {
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

    private boolean openLinkAndCheck(String linkName, String expectedText) {
        try {
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(linkName).setExact(false))
                    .click(new Locator.ClickOptions().setTimeout(2000));
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
