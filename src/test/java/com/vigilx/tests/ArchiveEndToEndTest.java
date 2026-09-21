package com.vigilx.tests;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.base.BaseTest;
import com.vigilx.config.ConfigReader;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.ArchiveExportValidation;
import com.vigilx.pages.AuditLogsPage;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.EventSearchValidation;
import com.vigilx.pages.LoginPage;

/**
 * Standalone run of the Archive part of the soak, same order as SoakHealthCheckRunner:
 * mvn test -Dtest=ArchiveEndToEndTest
 */
public class ArchiveEndToEndTest extends BaseTest {

    @Test
    public void runArchiveEndToEnd() {
        DashboardPage dashboard = new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
        Assert.assertTrue(dashboard.isDashboardLoaded(), "Dashboard should load after login.");

        String appUrl = ConfigReader.get("base.url").replace("/onboarding", "");
        ApplicationHealthPage healthPages = new ApplicationHealthPage(page);
        EventSearchValidation eventSearch = new EventSearchValidation(page);
        ArchiveExportValidation archiveExport = new ArchiveExportValidation(page);

        // Every step runs even if an earlier one fails, then all failures are reported together.
        StringBuilder failed = new StringBuilder();
        check(failed, "Archive", () -> healthPages.validateArchive(appUrl));
        check(failed, "Archive Camera Validation", () -> healthPages.validateArchiveCameras(appUrl));
        check(failed, "Event Search", eventSearch::validateEventSearch);
        check(failed, "Bookmark Search", eventSearch::validateBookmarkSearch);
        check(failed, "Archive Export", archiveExport::validateArchiveExport);
        check(failed, "Archive Export - Snapshots", archiveExport::validateArchiveSnapshotExport);
        check(failed, "Audit Logs - Archive Export", new AuditLogsPage(page)::runArchiveAuditLogsExportFlow);

        Assert.assertTrue(failed.length() == 0, "Failed steps:" + failed);
    }

    private static void check(StringBuilder failed, String name, java.util.function.BooleanSupplier step) {
        boolean ok;
        try {
            ok = step.getAsBoolean();
        } catch (Exception exception) {
            ok = false;
            System.err.println("[ARCHIVE E2E] " + name + " threw: " + exception.getMessage());
        }
        System.out.println("[ARCHIVE E2E] " + name + ": " + (ok ? "PASS" : "FAIL"));
        if (!ok) {
            failed.append("\n  - ").append(name);
        }
    }
}
