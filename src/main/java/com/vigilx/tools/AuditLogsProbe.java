package com.vigilx.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;

/** Throwaway diagnostic: explore Settings -> Users & Roles -> Audit Logs live. */
public final class AuditLogsProbe {

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
            page.getByText(Pattern.compile("users & roles", Pattern.CASE_INSENSITIVE)).first().click();
            page.waitForTimeout(1000);
            System.out.println("PROBE: url after Users & Roles click = " + page.url());

            // Dump tabs visible
            System.out.println("PROBE: tabs visible:");
            page.getByRole(AriaRole.TAB).all().forEach(t -> {
                try {
                    if (t.isVisible()) System.out.println("  TAB: [" + t.innerText().trim() + "]");
                } catch (Exception ignored) { }
            });

            Locator auditTab = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("Audit Logs").setExact(false)).first();
            if (auditTab.count() == 0) {
                System.out.println("PROBE: 'Audit Logs' tab not found under Users & Roles.");
            } else {
                auditTab.click();
                page.waitForTimeout(1200);
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-audit-1-landed.png")).setFullPage(true));
                System.out.println("PROBE: Audit Logs tab opened, url=" + page.url());

                System.out.println("PROBE: buttons visible on Audit Logs page:");
                page.getByRole(AriaRole.BUTTON).all().forEach(b -> {
                    try {
                        if (b.isVisible()) System.out.println("  BUTTON: [" + b.innerText().trim() + "]");
                    } catch (Exception ignored) { }
                });

                System.out.println("PROBE: column headers (columnheader role):");
                page.getByRole(AriaRole.COLUMNHEADER).all().forEach(c -> {
                    try {
                        System.out.println("  COL: [" + c.innerText().trim() + "]");
                    } catch (Exception ignored) { }
                });

                // Clear Date & Time filter
                Locator clearDate = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Clear Date & Time filter").setExact(false)).first();
                System.out.println("PROBE: 'Clear Date & Time filter' present=" + (clearDate.count() > 0));
                if (clearDate.count() > 0 && clearDate.isVisible()) {
                    clearDate.click();
                    page.waitForTimeout(600);
                    System.out.println("PROBE: clicked Clear Date & Time filter");
                }
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-audit-2-cleared.png")).setFullPage(true));

                // Export
                Locator exportButton = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Export audit logs").setExact(false)).first();
                System.out.println("PROBE: 'Export audit logs' present=" + (exportButton.count() > 0));
                if (exportButton.count() > 0 && exportButton.isVisible()) {
                    try {
                        Download download = page.waitForDownload(
                                new Page.WaitForDownloadOptions().setTimeout(20000),
                                () -> exportButton.click());
                        System.out.println("PROBE: download started, suggestedFilename=" + download.suggestedFilename());
                        Path saved = Paths.get("target/probe-audit-export-" + download.suggestedFilename());
                        download.saveAs(saved);
                        System.out.println("PROBE: saved to " + saved + " size=" + Files.size(saved));
                        if (Files.size(saved) > 0) {
                            String head;
                            try (var lines = Files.lines(saved)) {
                                head = lines.limit(3).reduce("", (a, b2) -> a + " || " + b2);
                            } catch (Exception readEx) {
                                head = "<binary or unreadable as text: " + readEx.getMessage() + ">";
                            }
                            System.out.println("PROBE: first lines: " + head);
                        }
                    } catch (Exception e) {
                        System.out.println("PROBE: export/download failed: " + e);
                    }
                }
                page.waitForTimeout(1000);
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/probe-audit-3-after-export.png")).setFullPage(true));
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
