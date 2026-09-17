package com.vigilx.tools;

import com.microsoft.playwright.Page;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LiveViewCrudPage;
import com.vigilx.pages.LoginPage;

/** Throwaway probe: runs the exact same runLiveViewCrudFlow(...) call SoakHealthCheckRunner makes,
 * using the corrected live.view.crud.camera.paths config value, to confirm the My View Create
 * regression is actually fixed end-to-end (create -> layout -> addCamera -> save config -> save
 * view -> rename -> delete). */
public final class LiveViewCrudFixProbe {
    private LiveViewCrudFixProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));

            LiveViewCrudPage liveViewCrud = new LiveViewCrudPage(page);
            String viewSuffix = String.valueOf(System.currentTimeMillis());
            String viewName = ConfigReader.getOrDefault("live.view.crud.name", "Live View") + " " + viewSuffix;
            String updatedViewName = viewName + ConfigReader.getOrDefault("live.view.crud.update.name.suffix", "-Update");
            String gridLayout = ConfigReader.getOrDefault("live.view.crud.grid.layout", "3x3");
            String[][] cameraPaths = parseCameraPaths(ConfigReader.getOrDefault("live.view.crud.camera.paths", ""));

            System.out.println("PROBE: cameraPaths.length=" + cameraPaths.length);
            for (String[] p : cameraPaths) {
                System.out.println("PROBE:   path=" + String.join(" > ", p));
            }

            boolean result = liveViewCrud.runLiveViewCrudFlow(viewName, updatedViewName, gridLayout, cameraPaths);
            System.out.println("PROBE: runLiveViewCrudFlow result=" + result);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
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
