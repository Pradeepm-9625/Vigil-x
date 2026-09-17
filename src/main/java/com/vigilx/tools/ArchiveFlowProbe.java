package com.vigilx.tools;

import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.ArchiveExportValidation;
import com.vigilx.pages.AuditLogsPage;
import com.vigilx.pages.EventSearchValidation;
import com.vigilx.pages.LoginPage;

/**
 * Throwaway probe: exercises ONLY the Archive portion SoakHealthCheckRunner runs - Archive ->
 * Archive Camera Validation -> Event Search -> Bookmark Search -> Archive Export -> Archive Export
 * Snapshots -> Audit Logs (Archive Export) - with per-step wall time printed, so Archive can be
 * checked without running the rest of the ~20-minute soak suite.
 */
public final class ArchiveFlowProbe {
    private ArchiveFlowProbe() {
    }

    public static void main(String[] args) throws Exception {
        long suiteStart = System.currentTimeMillis();
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            String baseUrl = ConfigReader.get("base.url");
            String appUrl = baseUrl.replace("/onboarding", "");
            page.navigate(baseUrl);
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            System.out.println("PROBE: login done at t+" + elapsed(suiteStart) + "s");

            ApplicationHealthPage healthPages = new ApplicationHealthPage(page);

            long t = System.currentTimeMillis();
            boolean archiveOk = healthPages.validateArchive(appUrl);
            System.out.println("PROBE: Archive = " + archiveOk + " (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            boolean archiveCamerasOk = healthPages.validateArchiveCameras(appUrl);
            System.out.println("PROBE: Archive Camera Validation = " + archiveCamerasOk + " (" + elapsed(t) + "s)");

            EventSearchValidation eventSearch = new EventSearchValidation(page);
            t = System.currentTimeMillis();
            boolean eventSearchOk = eventSearch.validateEventSearch();
            System.out.println("PROBE: Event Search = " + eventSearchOk + " (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            boolean bookmarkSearchOk = eventSearch.validateBookmarkSearch();
            System.out.println("PROBE: Bookmark Search = " + bookmarkSearchOk + " (" + elapsed(t) + "s)");

            ArchiveExportValidation archiveExport = new ArchiveExportValidation(page);
            t = System.currentTimeMillis();
            boolean archiveExportOk = archiveExport.validateArchiveExport();
            System.out.println("PROBE: Archive Export = " + archiveExportOk + " (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            boolean archiveSnapshotExportOk = archiveExport.validateArchiveSnapshotExport();
            System.out.println("PROBE: Archive Export - Snapshots = " + archiveSnapshotExportOk + " (" + elapsed(t) + "s)");

            AuditLogsPage archiveAuditLogs = new AuditLogsPage(page);
            t = System.currentTimeMillis();
            boolean archiveAuditLogsOk = archiveAuditLogs.runArchiveAuditLogsExportFlow();
            System.out.println("PROBE: Audit Logs - Archive Export = " + archiveAuditLogsOk + " (" + elapsed(t) + "s)");

            System.out.println("PROBE: TOTAL Archive portion = " + elapsed(suiteStart) + "s");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }

    private static long elapsed(long since) {
        return (System.currentTimeMillis() - since) / 1000;
    }
}
