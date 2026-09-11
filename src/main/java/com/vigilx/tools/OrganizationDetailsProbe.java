package com.vigilx.tools;

import java.nio.file.Paths;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;

/** Throwaway diagnostic: explore Settings -> Organisation -> logo/admin/contact/address edit flows. */
public final class OrganizationDetailsProbe {

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            page.waitForTimeout(1500);

            Locator openSettings = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Open settings").setExact(false)).first();
            if (openSettings.count() > 0 && openSettings.isVisible()) {
                openSettings.click();
                page.waitForTimeout(800);
            }
            page.getByText(Pattern.compile("organisation|organization", Pattern.CASE_INSENSITIVE)).first().click();
            page.waitForTimeout(1200);

            // ---- Logo flow ----
            Locator editLogo = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Edit organization logo")).first();
            editLogo.click();
            page.waitForTimeout(600);
            Locator fileInput = page.locator("input[type=\"file\"]").first();
            fileInput.setInputFiles(Paths.get("src/test/resources/Logo.jpg").toAbsolutePath());
            page.waitForTimeout(1000);
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-orgdet-1-cropper.png")).setFullPage(true));

            System.out.println("PROBE: buttons visible in cropper dialog:");
            page.getByRole(AriaRole.BUTTON).all().forEach(b -> {
                try { if (b.isVisible()) System.out.println("  BTN: [" + b.innerText().trim() + "]"); } catch (Exception ignored) { }
            });
            System.out.println("PROBE: .image-cropper-selection-border count=" + page.locator(".image-cropper-selection-border").count());

            Locator cropBorder = page.locator(".image-cropper-selection-border").first();
            if (cropBorder.count() > 0) {
                try { cropBorder.click(); System.out.println("PROBE: clicked cropper border"); } catch (Exception e) { System.out.println("PROBE: cropper border click failed: " + e.getMessage()); }
            }

            Locator saveChanges1 = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save changes").setExact(false)).first();
            System.out.println("PROBE: 'Save changes' present after upload=" + (saveChanges1.count() > 0 && saveChanges1.isVisible()));
            if (saveChanges1.count() > 0 && saveChanges1.isVisible()) {
                saveChanges1.click();
                page.waitForTimeout(800);
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-orgdet-2-after-savechanges.png")).setFullPage(true));
                System.out.println("PROBE: buttons visible after 'Save changes':");
                page.getByRole(AriaRole.BUTTON).all().forEach(b -> {
                    try { if (b.isVisible()) System.out.println("  BTN: [" + b.innerText().trim() + "]"); } catch (Exception ignored) { }
                });
            }

            Locator saveFinal = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save").setExact(true)).first();
            System.out.println("PROBE: exact 'Save' present=" + (saveFinal.count() > 0 && saveFinal.isVisible()));
            if (saveFinal.count() > 0 && saveFinal.isVisible()) {
                saveFinal.click();
                page.waitForTimeout(1000);
            }
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-orgdet-3-after-save.png")).setFullPage(true));

            System.out.println("PROBE: any toast-like element:");
            page.locator("[class*='toast']").all().forEach(t -> {
                try { System.out.println("  TOAST: [" + t.innerText().trim() + "] id=" + t.getAttribute("id")); } catch (Exception ignored) { }
            });

            page.waitForTimeout(1500);

            // ---- Admin Details ----
            Locator editAdmin = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Edit Admin Details")).first();
            System.out.println("PROBE: 'Edit Admin Details' present=" + (editAdmin.count() > 0));
            if (editAdmin.count() > 0) {
                editAdmin.click();
                page.waitForTimeout(800);
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-orgdet-4-admin-edit.png")).setFullPage(true));
                System.out.println("PROBE: textboxes visible in Admin Details editor:");
                page.getByRole(AriaRole.TEXTBOX).all().forEach(t -> {
                    try {
                        if (t.isVisible()) {
                            String label = t.getAttribute("aria-label");
                            System.out.println("  TEXTBOX aria-label=[" + label + "] value=[" + t.inputValue() + "]");
                        }
                    } catch (Exception ignored) { }
                });
                System.out.println("PROBE: buttons visible in Admin Details editor:");
                page.getByRole(AriaRole.BUTTON).all().forEach(b -> {
                    try { if (b.isVisible()) System.out.println("  BTN: [" + b.innerText().trim() + "]"); } catch (Exception ignored) { }
                });
                // Close without saving - just dump info for now, don't mutate real admin data yet.
                Locator cancel = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("^cancel$", Pattern.CASE_INSENSITIVE))).first();
                if (cancel.count() > 0 && cancel.isVisible()) {
                    cancel.click();
                    page.waitForTimeout(500);
                } else {
                    page.keyboard().press("Escape");
                    page.waitForTimeout(500);
                }
            }

            // ---- Primary Contact ----
            Locator editPrimary = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Edit Primary Contact")).first();
            System.out.println("PROBE: 'Edit Primary Contact' present=" + (editPrimary.count() > 0));
            if (editPrimary.count() > 0) {
                editPrimary.click();
                page.waitForTimeout(800);
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-orgdet-5-contact-edit.png")).setFullPage(true));
                System.out.println("PROBE: textboxes visible in Primary Contact editor:");
                page.getByRole(AriaRole.TEXTBOX).all().forEach(t -> {
                    try {
                        if (t.isVisible()) {
                            String label = t.getAttribute("aria-label");
                            System.out.println("  TEXTBOX aria-label=[" + label + "] value=[" + t.inputValue() + "]");
                        }
                    } catch (Exception ignored) { }
                });
                Locator cancel = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("^cancel$", Pattern.CASE_INSENSITIVE))).first();
                if (cancel.count() > 0 && cancel.isVisible()) {
                    cancel.click();
                    page.waitForTimeout(500);
                } else {
                    page.keyboard().press("Escape");
                    page.waitForTimeout(500);
                }
            }

            // ---- Address Details ----
            Locator editAddress = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Edit Address Details")).first();
            System.out.println("PROBE: 'Edit Address Details' present=" + (editAddress.count() > 0));
            if (editAddress.count() > 0) {
                editAddress.click();
                page.waitForTimeout(800);
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-orgdet-6-address-edit.png")).setFullPage(true));
                System.out.println("PROBE: textboxes visible in Address Details editor:");
                page.getByRole(AriaRole.TEXTBOX).all().forEach(t -> {
                    try {
                        if (t.isVisible()) {
                            String label = t.getAttribute("aria-label");
                            System.out.println("  TEXTBOX aria-label=[" + label + "] value=[" + t.inputValue() + "]");
                        }
                    } catch (Exception ignored) { }
                });
                Locator cancel = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName(Pattern.compile("^cancel$", Pattern.CASE_INSENSITIVE))).first();
                if (cancel.count() > 0 && cancel.isVisible()) {
                    cancel.click();
                    page.waitForTimeout(500);
                } else {
                    page.keyboard().press("Escape");
                    page.waitForTimeout(500);
                }
            }

        } catch (Exception exception) {
            System.out.println("PROBE ERROR: " + exception);
            exception.printStackTrace();
        } finally {
            page.waitForTimeout(1500);
            PlaywrightFactory.closeBrowser();
        }
    }
}
