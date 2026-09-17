package com.vigilx.reporting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Owns one soak execution's evidence folder under {@code target/soak-test/}.
 *
 * <p>Each execution gets its own {@code run-<timestamp>} directory so earlier evidence is never
 * overwritten or deleted:
 * <pre>
 * target/soak-test/run-20260819-183000/
 *     api-failures/     page-failures/     stream-failures/
 *     final-report/
 *     screenshots/{Page}/{Tab}/   - created lazily, only once a genuine failure captures one
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

    /**
     * Ordered {@code stepName} prefix -&gt; "Page[/Tab]" table, most specific prefix first (several
     * real {@code validatePage} names share a common prefix - e.g. every "Users & Roles - ..."
     * lifecycle name also starts with the bare "Users & Roles" - so the longer, more specific entries
     * must be tested first). Built from the actual step names {@link com.vigilx.soak.SoakHealthCheckRunner}
     * passes today, so evidence lands under real page/tab names rather than invented ones. Anything
     * not covered here falls back to splitting the name on its own " - " separator (or "Other" for a
     * completely unmapped name), so a future validation still gets a sensible folder without needing
     * this table updated first.
     */
    private static final String[][] PAGE_TAB_PREFIXES = {
            {"Archive Export - Snapshots", "Export", "Snapshots"},
            {"Archive Export", "Export", "Videos"},
            {"Archive Camera Validation", "Archive", ""},
            {"Archive", "Archive", ""},
            {"Map Camera Validation", "Maps", ""},
            {"Map", "Maps", ""},
            {"Event Search", "Search", "Events"},
            {"Bookmark Search", "Search", "Bookmarks"},
            {"Project Hierarchy - Node & Site Edit", "ProjectHierarchy", "NodeSiteEdit"},
            {"Project Hierarchy - Audit Logs", "ProjectHierarchy", "AuditLogs"},
            {"Project Hierarchy", "ProjectHierarchy", ""},
            {"Device Details", "ApplicationSettings", "Device"},
            {"Device Tabs", "ApplicationSettings", "Device"},
            {"Devices", "ApplicationSettings", "Device"},
            {"Master Configuration", "ApplicationSettings", "MasterConfiguration"},
            {"Reports", "ApplicationSettings", "Reports"},
            {"Application Settings - Enable QC", "ApplicationSettings", "QC"},
            {"Application Settings - Enable VA Alerts", "ApplicationSettings", "VAAlerts"},
            {"Users & Roles - Create User", "UsersRoles", "Users"},
            {"Users & Roles - User Lifecycle", "UsersRoles", "Users"},
            {"Users & Roles - Role Lifecycle", "UsersRoles", "Roles"},
            {"Users & Roles - Group Lifecycle", "UsersRoles", "Groups"},
            {"Users & Roles - Audit Logs Export", "UsersRoles", "AuditLogs"},
            {"Users & Roles", "UsersRoles", ""},
            {"Organisation - Update Details", "Organization", "Details"},
            {"Organisation - Project Information", "Project", ""},
            {"Organisation - License", "License", ""},
            {"Organisation - Audit Logs Export", "Organization", "AuditLogs"},
            {"Organisation", "Organization", ""},
            {"License", "License", ""},
            {"QC", "QC", ""},
            {"Event Acknowledgement", "EventAcknowledgement", ""},
            {"Live View - CRUD", "LiveView", ""},
            {"Live View", "LiveView", ""},
            {"Sequence", "Sequence", ""},
            {"Audit Logs", "AuditLogs", ""},
    };

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
            // screenshots/ is intentionally NOT pre-created here (legacy flat folders or the
            // per-Page/Tab tree): a PASS-only run must never leave behind an empty screenshots/
            // directory. Every real capture path already creates its own target directory lazily,
            // at the moment a genuine failure screenshot is taken - ScreenshotUtils.captureTo() and
            // .captureFailure() both already do this (Files.createDirectories(...) right before
            // page.screenshot(...)), so nothing else needs to change for that to keep working.
            // logs/ is unrelated to this and still pre-created as before.
            for (String folder : knownPageTabFolders()) {
                Files.createDirectories(directory.resolve("logs").resolve(Paths.get(folder)));
            }

            // Redirect the validators that already read these keys; no edits needed in those classes.
            // Pointed at the SAME Page/Tab folders as everything else (Maps/, Archive/, LiveView/)
            // rather than the older flat maps/archive/live-view ones, so their own existing
            // screenshot handling lands in the unified Soak Test folder structure too.
            Path screenshots = directory.resolve("screenshots");
            System.setProperty("soak.screenshot.root", screenshots.toString());
            System.setProperty("map.screenshot.directory", screenshots.resolve(screenshotFolder("Map")).toString());
            System.setProperty("archive.screenshot.directory",
                    screenshots.resolve(screenshotFolder("Archive")).toString());
            System.setProperty("liveview.screenshot.directory",
                    screenshots.resolve(screenshotFolder("Live View")).toString());

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

    /** Screenshot folder for a page/tab, organized under the Soak Test result folder. */
    public Path screenshotDirectory(String stepName) {
        return runDirectory.resolve("screenshots").resolve(screenshotFolder(stepName));
    }

    /** Log folder mirroring {@link #screenshotDirectory(String)}, under {@code logs/} instead. */
    public Path logDirectory(String stepName) {
        return runDirectory.resolve("logs").resolve(screenshotFolder(stepName));
    }

    /**
     * Maps a {@code validatePage} step name onto its real Page[/Tab] folder (e.g.
     * {@code "Users & Roles - Create User"} -&gt; {@code "UsersRoles/Users"}), using the actual
     * step names {@link com.vigilx.soak.SoakHealthCheckRunner} passes today ({@link
     * #PAGE_TAB_PREFIXES}) rather than inventing modules that do not exist. A name outside that
     * table falls back to splitting on its own " - " separator (page/tab, sanitized), and a
     * completely blank name lands in {@code Other} - never creating a per-run one-off folder for a
     * name variant that will not repeat.
     */
    public static String screenshotFolder(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            return "Other";
        }
        for (String[] entry : PAGE_TAB_PREFIXES) {
            if (stepName.startsWith(entry[0])) {
                String page = entry[1];
                String tab = entry[2];
                return tab.isBlank() ? page : page + "/" + tab;
            }
        }
        // Fallback for a name not yet catalogued above: derive Page[/Tab] from the name's own
        // " - " separator (the same convention every catalogued step name already follows).
        int separator = stepName.indexOf(" - ");
        String page = sanitizeFolderName(separator > 0 ? stepName.substring(0, separator) : stepName);
        String tab = separator > 0 ? sanitizeFolderName(stepName.substring(separator + 3)) : null;
        if (page.isBlank()) {
            return "Other";
        }
        return (tab == null || tab.isBlank()) ? page : page + "/" + tab;
    }

    /** Every Page[/Tab] folder this table already knows about, for pre-creating the tree upfront. */
    private static Set<String> knownPageTabFolders() {
        Set<String> folders = new LinkedHashSet<>();
        for (String[] entry : PAGE_TAB_PREFIXES) {
            String page = entry[1];
            String tab = entry[2];
            folders.add(tab.isBlank() ? page : page + "/" + tab);
        }
        return folders;
    }

    /** Strips a step-name fragment down to a filesystem-safe folder segment (letters/digits only). */
    private static String sanitizeFolderName(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[^A-Za-z0-9]+", "");
        return cleaned.isBlank() ? "Other" : cleaned;
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
