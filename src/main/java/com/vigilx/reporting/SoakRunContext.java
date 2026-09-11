package com.vigilx.reporting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Owns one soak execution's evidence folder under {@code target/soak-test/}.
 *
 * <p>Each execution gets its own {@code run-<timestamp>} directory so earlier evidence is never
 * overwritten or deleted:
 * <pre>
 * target/soak-test/run-20260819-183000/
 *     api-failures/     page-failures/     stream-failures/
 *     screenshots/{device,alerts,live-view,maps,archive,playback,project-hierarchy,dashboard,other}
 *     final-report/
 * </pre>
 *
 * <p>Existing validators are redirected into this folder <em>without code changes</em> by setting
 * the system properties they already read through {@code ConfigReader} - which resolves
 * {@code System.getProperty(key, ...)} first. That keeps {@code MapValidation},
 * {@code ArchiveValidation} and {@code LiveViewMonitor} untouched.
 *
 * <p>Never throws: if the folder cannot be created the run continues and paths fall back to the
 * fixed legacy locations.
 */
public final class SoakRunContext {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String ROOT = "target/soak-test";

    /** Screenshot sub-folders, matching the agreed report structure. */
    private static final String[] SCREENSHOT_FOLDERS = {
            "device", "alerts", "live-view", "maps", "archive", "playback",
            "project-hierarchy", "dashboard", "other"};

    private static volatile SoakRunContext current;

    private final Path runDirectory;
    private final LocalDateTime startedAt;

    private SoakRunContext(Path runDirectory, LocalDateTime startedAt) {
        this.runDirectory = runDirectory;
        this.startedAt = startedAt;
    }

    /**
     * Creates a fresh run folder and redirects the existing screenshot configuration into it.
     * Safe to call once per soak execution; never throws.
     */
    public static synchronized SoakRunContext start() {
        LocalDateTime now = LocalDateTime.now();
        Path directory = Paths.get(ROOT, "run-" + now.format(RUN_ID));
        SoakRunContext context = new SoakRunContext(directory, now);
        try {
            Files.createDirectories(directory.resolve("api-failures"));
            Files.createDirectories(directory.resolve("page-failures"));
            Files.createDirectories(directory.resolve("stream-failures"));
            Files.createDirectories(directory.resolve("final-report"));
            for (String folder : SCREENSHOT_FOLDERS) {
                Files.createDirectories(directory.resolve("screenshots").resolve(folder));
            }

            // Redirect the validators that already read these keys; no edits needed in those classes.
            Path screenshots = directory.resolve("screenshots");
            System.setProperty("soak.screenshot.root", screenshots.toString());
            System.setProperty("map.screenshot.directory", screenshots.resolve("maps").toString());
            System.setProperty("archive.screenshot.directory", screenshots.resolve("archive").toString());
            System.setProperty("liveview.screenshot.directory", screenshots.resolve("live-view").toString());

            // Breadcrumb so the newest evidence folder is easy to find.
            Files.writeString(Paths.get(ROOT, "latest-run.txt"),
                    directory.toAbsolutePath() + System.lineSeparator());

            System.out.println("[SOAK REPORT] Evidence folder: " + directory.toAbsolutePath());
        } catch (Exception exception) {
            System.err.println("[SOAK REPORT] Could not prepare the run folder: " + exception.getMessage());
        }
        current = context;
        return context;
    }

    /** The active run, creating one on demand so reporting never fails for want of a context. */
    public static SoakRunContext current() {
        SoakRunContext context = current;
        if (context == null) {
            synchronized (SoakRunContext.class) {
                if (current == null) {
                    start();
                }
                context = current;
            }
        }
        return context;
    }

    /** {@code target/soak-test}, the parent of every {@code run-<timestamp>} folder. */
    public static Path root() {
        return Paths.get(ROOT);
    }

    public Path runDirectory() {
        return runDirectory;
    }

    public LocalDateTime startedAt() {
        return startedAt;
    }

    public Path apiFailureDirectory() {
        return runDirectory.resolve("api-failures");
    }

    public Path pageFailureDirectory() {
        return runDirectory.resolve("page-failures");
    }

    public Path streamFailureDirectory() {
        return runDirectory.resolve("stream-failures");
    }

    public Path finalReportDirectory() {
        return runDirectory.resolve("final-report");
    }

    /** Screenshot folder for a page, mapped onto the agreed folder names. */
    public Path screenshotDirectory(String pageName) {
        return runDirectory.resolve("screenshots").resolve(screenshotFolder(pageName));
    }

    /**
     * Maps a validation name onto one of the fixed screenshot folders. Unknown pages land in
     * {@code other} rather than creating a new folder per name.
     */
    public static String screenshotFolder(String pageName) {
        if (pageName == null || pageName.isBlank()) {
            return "other";
        }
        String name = pageName.toLowerCase(Locale.ROOT);
        if (name.contains("live view") || name.contains("live-view")) {
            return "live-view";
        }
        if (name.contains("map")) {
            return "maps";
        }
        if (name.contains("archive")) {
            return "archive";
        }
        if (name.contains("playback")) {
            return "playback";
        }
        if (name.contains("alert")) {
            return "alerts";
        }
        if (name.contains("device")) {
            return "device";
        }
        if (name.contains("hierarchy")) {
            return "project-hierarchy";
        }
        if (name.contains("dashboard")) {
            return "dashboard";
        }
        return "other";
    }

    /** Path rendered relative to the project root, for readable report entries. */
    public static String relative(String absolutePath) {
        if (absolutePath == null) {
            return null;
        }
        try {
            Path working = Paths.get("").toAbsolutePath();
            Path target = Paths.get(absolutePath).toAbsolutePath();
            return target.startsWith(working)
                    ? working.relativize(target).toString().replace('\\', '/')
                    : absolutePath.replace('\\', '/');
        } catch (Exception exception) {
            return absolutePath;
        }
    }
}
