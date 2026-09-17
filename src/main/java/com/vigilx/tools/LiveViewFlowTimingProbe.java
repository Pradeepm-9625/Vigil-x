package com.vigilx.tools;

import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.monitoring.LiveViewMonitor;
import com.vigilx.pages.LiveViewCrudPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.Live_view;
import com.vigilx.pages.SequencePage;

/**
 * Throwaway probe: exercises ONLY Live View -> Stream Monitoring -> Bookmark -> Snapshot -> CRUD ->
 * Sequence, the exact same calls SoakHealthCheckRunner makes for that portion, with per-step wall
 * time printed - so the Live View/Sequence timing fix can be validated without running the rest of
 * the ~20-minute soak suite (QC, Devices, License, Archive, ...).
 */
public final class LiveViewFlowTimingProbe {
    private LiveViewFlowTimingProbe() {
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

            long t = System.currentTimeMillis();
            boolean liveViewOk = new Live_view(page).validateLiveView(appUrl);
            System.out.println("PROBE: Live View = " + liveViewOk + " (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            LiveViewMonitor.MonitorResult monitorResult = new LiveViewMonitor(page).monitor();
            System.out.println("PROBE: Stream Monitoring passed=" + monitorResult.isPassed()
                    + " iterations=" + monitorResult.iterations() + " (" + elapsed(t) + "s)");

            LiveViewCrudPage liveViewCrud = new LiveViewCrudPage(page);
            t = System.currentTimeMillis();
            liveViewCrud.navigateToLiveView();
            System.out.println("PROBE: navigateToLiveView (pre-Snapshot) (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            boolean snapshotOk = liveViewCrud.createSnapshotOnCurrentCamera("Testing Snapshot " + System.currentTimeMillis());
            System.out.println("PROBE: Snapshot = " + snapshotOk + " (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            boolean bookmarkOk = liveViewCrud.createBookmarkOnCurrentCamera("Testing Bookmark " + System.currentTimeMillis());
            System.out.println("PROBE: Bookmark = " + bookmarkOk + " (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            String viewSuffix = String.valueOf(System.currentTimeMillis());
            String viewName = ConfigReader.getOrDefault("live.view.crud.name", "Live View") + " " + viewSuffix;
            String updatedViewName = viewName + ConfigReader.getOrDefault("live.view.crud.update.name.suffix", "-Update");
            String gridLayout = ConfigReader.getOrDefault("live.view.crud.grid.layout", "3x3");
            String[][] cameraPaths = parseCameraPaths(ConfigReader.getOrDefault("live.view.crud.camera.paths", ""));
            boolean crudOk = liveViewCrud.runLiveViewCrudFlow(viewName, updatedViewName, gridLayout, cameraPaths);
            System.out.println("PROBE: Live View CRUD = " + crudOk + " (" + elapsed(t) + "s)");

            t = System.currentTimeMillis();
            SequencePage sequence = new SequencePage(page);
            String sequenceSuffix = String.valueOf(System.currentTimeMillis());
            String sequenceName = ConfigReader.getOrDefault("sequence.crud.name", "Test Sequence") + " " + sequenceSuffix;
            String updatedSequenceName = sequenceName + ConfigReader.getOrDefault("sequence.crud.update.name.suffix", "-Update");
            String[][] sequenceCameraPaths = parseCameraPaths(ConfigReader.getOrDefault("sequence.crud.camera.paths", ""));
            boolean sequenceOk = sequence.runSequenceLifecycle(sequenceName, updatedSequenceName, sequenceCameraPaths);
            System.out.println("PROBE: Sequence = " + sequenceOk + " (" + elapsed(t) + "s)");

            System.out.println("PROBE: TOTAL Live View+Sequence portion = " + elapsed(suiteStart) + "s");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }

    private static long elapsed(long since) {
        return (System.currentTimeMillis() - since) / 1000;
    }

    private static String[][] parseCameraPaths(String config) {
        if (config == null || config.isBlank()) {
            return new String[0][];
        }
        String[] entries = config.split(";");
        String[][] paths = new String[entries.length][];
        for (int i = 0; i < entries.length; i++) {
            String[] segments = entries[i].split(">");
            for (int j = 0; j < segments.length; j++) {
                segments[j] = segments[j].trim();
            }
            paths[i] = segments;
        }
        return paths;
    }
}
