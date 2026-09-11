package com.vigilx.pages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;
import com.vigilx.reporting.SoakReporter;
import com.vigilx.utils.ScreenshotUtils;

/**
 * Robust, soak-safe validation of the Archive/Playback page: opens the device tree, filters to the
 * active devices, adds a randomly chosen one and verifies its recording actually plays.
 *
 * <p>Additional to the existing {@link ApplicationHealthPage#validateArchive} and
 * {@link ApplicationHealthPage#addCameraToPlayback}, both of which are left untouched.
 *
 * <p>Contract: never throws into the caller. Failures are logged, screenshotted under
 * {@code target/soak-test/screenshots/archive} and returned as {@code false} so the soak continues.
 */
public class ArchiveValidation extends BasePage {

    private static final String SEPARATOR = "============================================================";
    private static final String SUB_SEPARATOR = "------------------------------------------------------------";

    private static final String DEFAULT_SCREENSHOT_DIRECTORY = "target/soak-test/screenshots/archive";
    private static final int DEFAULT_TREE_TIMEOUT_MS = 30000;

    private static final double PLAYBACK_TOLERANCE_SECONDS = 0.2;

    /** How long the recording must keep progressing for; overridable via archive.playback.monitor.seconds. */
    private static final int DEFAULT_MONITOR_SECONDS = 60;
    /** Poll cadence during that window; overridable via archive.playback.monitor.interval.ms. */
    private static final int DEFAULT_MONITOR_INTERVAL_MS = 2000;
    /** Consecutive non-progressing samples tolerated before the stream counts as stalled. */
    private static final int DEFAULT_MONITOR_STALL_SAMPLES = 3;
    /**
     * How long a just-added stream is given to hand back its first decodable frame before it is
     * declared failed; overridable via archive.playback.startup.timeout.seconds. A freshly added
     * camera can take a while (manifest fetch, decoder init) even when perfectly healthy, so this
     * polls instead of taking one early snapshot and failing on it.
     */
    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 60;
    /** Poll cadence while waiting for the stream to start. */
    private static final long STARTUP_POLL_INTERVAL_MS = 1000L;

    /** Markers that mean "this device is usable"; overridable via archive.online.pattern. */
    private static final String DEFAULT_ONLINE_PATTERN =
            "online|connected|streaming|recording|status[-_]?(ok|up|green|online)";

    /** Markers that mean "do not pick this device"; overridable via archive.offline.pattern. */
    private static final String DEFAULT_OFFLINE_PATTERN =
            "offline|disconnected|unreachable|unavailable|inactive|not\\s*connected|no\\s*signal"
                    + "|status[-_]?(off|down|red|offline)";

    /** Walks a checkbox up to its own tree row and returns that row's markup, minus child rows. */
    private static final String ROW_SIGNATURE_SCRIPT =
            "el => {"
                    + "  const row = el.closest(\"li, [role='treeitem'], .MuiTreeItem-root,"
                    + " .ph-v1-dynamic-tree-node\") || el.parentElement || el;"
                    + "  const clone = row.cloneNode(true);"
                    + "  clone.querySelectorAll(\"ul, [role='group']\")"
                    + "       .forEach(child => child.remove());"
                    + "  return (clone.outerHTML || '').slice(0, 4000);"
                    + "}";

    /** Same row-walk as {@link #ROW_SIGNATURE_SCRIPT}, but returns visible text instead of markup. */
    private static final String ROW_TEXT_SCRIPT =
            "el => {"
                    + "  const row = el.closest(\"li, [role='treeitem'], .MuiTreeItem-root,"
                    + " .ph-v1-dynamic-tree-node\") || el.parentElement || el;"
                    + "  const clone = row.cloneNode(true);"
                    + "  clone.querySelectorAll(\"ul, [role='group']\")"
                    + "       .forEach(child => child.remove());"
                    + "  return (clone.textContent || '').slice(0, 200);"
                    + "}";

    private final Path screenshotDirectory;
    private final int treeTimeoutMs;

    public ArchiveValidation(Page page) {
        super(page);
        this.screenshotDirectory = Paths.get(
                ConfigReader.getOrDefault("archive.screenshot.directory", DEFAULT_SCREENSHOT_DIRECTORY));
        this.treeTimeoutMs = intConfig("archive.device.tree.timeout.ms", DEFAULT_TREE_TIMEOUT_MS);
    }

    /** One selectable device in the add-camera tree. */
    private record TreeDevice(String name, int checkboxIndex) { }

    /** Rolling status of the whole validation, rendered as the closing report. */
    private static final class ArchiveReport {
        private String archivePage = "FAIL";
        private String archiveData = "SKIPPED";
        private String addCameraDialog = "SKIPPED";
        private String deviceTree = "SKIPPED";
        private String activeFilter = "SKIPPED";
        private int devicesFound;
        private String selectedDevice = "<none>";
        private String checkboxSelected = "SKIPPED";
        private String saveChanges = "SKIPPED";
        private String cameraAdded = "SKIPPED";
        private String videoElement = "SKIPPED";
        private String videoSource = "SKIPPED";
        private String readyStateStatus = "SKIPPED";
        private String resolution = "0x0";
        private String playback = "SKIPPED";
        private String sourceValue = "<empty>";
        private int readyState;
        private String failureReason;
        private String screenshot;
        private boolean passed;
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Runs the full Archive/Playback validation. Safe to call repeatedly; each iteration picks a
     * fresh random device.
     *
     * @return {@code true} only when the page, tree, camera add and recording playback all passed
     */
    public boolean validateArchive(String baseUrl) {

        ArchiveReport report = new ArchiveReport();
        int apiFailuresBefore = ApiMonitor.getDistinctFailureCount();
        String deviceForCorrelation = "<none>";

        System.out.println(SEPARATOR);
        System.out.println("ARCHIVE / PLAYBACK VALIDATION STARTED");
        System.out.println(SEPARATOR);

        try {
            // STEP 1 - Archive page loaded
            if (!openArchivePage(baseUrl)) {
                fail(report, "Archive page did not load", "archive-not-loaded");
                return finish(report);
            }
            report.archivePage = "PASS";

            // STEP 2 - let the archive's own data settle before touching the UI
            report.archiveData = waitForArchiveData() ? "PASS" : "WARN";

            // STEP 3 + 4 - open Add Camera and wait for the device-tree API response
            if (!openAddCameraDialog()) {
                fail(report, "Add Camera control could not be opened", "add-camera-not-opened");
                return finish(report);
            }
            report.addCameraDialog = "PASS";

            // STEP 5 - the tree must actually be populated
            if (!validateDeviceTree()) {
                fail(report, "Device tree did not populate", "device-tree-empty");
                return finish(report);
            }
            report.deviceTree = "PASS";

            // STEP 6 - narrow to active devices where the UI offers that filter
            report.activeFilter = applyActiveFilter() ? "APPLIED" : "NOT AVAILABLE";

            // STEP 7 - discover what is selectable
            List<TreeDevice> devices = discoverDevices();
            report.devicesFound = devices.size();
            if (devices.isEmpty()) {
                fail(report, "No active devices available in the device tree", "no-active-devices");
                return finish(report);
            }

            // STEP 8 - random selection among the online devices keeps a long soak from always
            // testing the same camera without ever landing on an offline one.
            TreeDevice device = selectRandomOnlineDevice(devices);
            if (device == null) {
                fail(report, "No online device available in the device tree", "no-online-devices");
                return finish(report);
            }
            report.selectedDevice = device.name();
            deviceForCorrelation = device.name();

            // STEP 9 - tick its checkbox
            if (!selectDevice(device)) {
                fail(report, "Device checkbox could not be selected", slug(device.name()) + "-checkbox-failed");
                return finish(report);
            }
            report.checkboxSelected = "PASS";

            // STEP 10 - commit the selection
            if (!saveSelection()) {
                fail(report, "Save changes / Add camera could not be clicked",
                        slug(device.name()) + "-save-failed");
                return finish(report);
            }
            report.saveChanges = "PASS";

            // STEP 11 - the camera must actually appear in the playback area
            if (!validateCameraAdded(device.name())) {
                fail(report, "Camera was not added to the playback area", slug(device.name()) + "-not-added");
                return finish(report);
            }
            report.cameraAdded = "PASS";

            // STEP 12 - validate the recording actually plays
            validatePlaybackStream(report, device.name());
            return finish(report);

        } catch (Exception exception) {
            // Defensive: nothing in here may end the soak run.
            fail(report, "Archive validation error: " + exception.getMessage(), "archive-validation-exception");
            return finish(report);

        } finally {
            // STEP 13 - correlate whatever the central API monitor recorded during this validation.
            reportCorrelatedApiFailures(deviceForCorrelation, apiFailuresBefore);
        }
    }

    // ---------------------------------------------------------------------
    // STEP 1 + 2 - page and data
    // ---------------------------------------------------------------------

    private boolean openArchivePage(String baseUrl) {
        try {
            navigateTo(baseUrl + "/live-views/archive");
            System.out.println("[INFO] Archive page opened: " + baseUrl + "/live-views/archive");
        } catch (Exception exception) {
            System.err.println("[FAIL] Could not navigate to the Archive page: " + exception.getMessage());
            return false;
        }

        try {
            Locator playbackHeading = page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Playback").setExact(true));
            if (playbackHeading.count() > 0) {
                playbackHeading.first().waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(20000));
                System.out.println("[PASS] Archive/Playback page is loaded.");
                return true;
            }

            // Fall back to the page shell for deployments that label the heading differently.
            page.locator("[class*='archive' i], [class*='playback' i], .vxpanelcard__body").first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(20000));
            System.out.println("[PASS] Archive page shell is visible.");
            return true;

        } catch (Exception exception) {
            System.err.println("[FAIL] Archive page did not load: " + exception.getMessage());
            return false;
        }
    }

    /** Waits for the archive's initial data calls to go quiet before interacting. */
    private boolean waitForArchiveData() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(20000));
            System.out.println("[PASS] Archive page data finished loading.");
            return true;
        } catch (Exception exception) {
            // A permanently streaming page never goes idle; that is not a failure by itself.
            System.out.println("[WARN] Archive page never reached network idle; continuing.");
            page.waitForTimeout(3000);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // STEP 3 + 4 - Add Camera and the device-tree response
    // ---------------------------------------------------------------------

    /** Clicks Add Camera while waiting for the device-tree API response it triggers. */
    private boolean openAddCameraDialog() {
        Locator addCamera = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(java.util.regex.Pattern.compile(
                        "add\\s+camera", java.util.regex.Pattern.CASE_INSENSITIVE))).first();

        if (addCamera.count() == 0) {
            System.err.println("[FAIL] Add Camera button is not present on the Archive page.");
            return false;
        }

        try {
            Response response = page.waitForResponse(
                    this::isDeviceTreeResponse,
                    new Page.WaitForResponseOptions().setTimeout(treeTimeoutMs),
                    () -> addCamera.click(new Locator.ClickOptions().setTimeout(10000)));

            System.out.println("[PASS] Device-tree API responded: " + response.status() + " " + response.url());
            return true;

        } catch (Exception exception) {
            // The click inside the callback already happened; only the response match timed out.
            System.out.println("[WARN] No device-tree API response matched within "
                    + treeTimeoutMs + "ms; continuing on the rendered DOM.");
            page.waitForTimeout(3000);
            return true;
        }
    }

    /** Heuristic for the call that backs the device tree; kept broad so it survives API renames. */
    private boolean isDeviceTreeResponse(Response response) {
        String url = response.url().toLowerCase(Locale.ROOT);
        return url.contains("device") || url.contains("hierarchy") || url.contains("tree")
                || url.contains("camera") || url.contains("site");
    }

    // ---------------------------------------------------------------------
    // STEP 5 - device tree
    // ---------------------------------------------------------------------

    private boolean validateDeviceTree() {
        try {
            Locator tree = page.locator(
                    "[id*='mui-tree-view'], [role='tree'], .MuiTreeView-root, .ph-v1-dynamic-tree-node,"
                            + " .MuiTreeItem-content").first();

            tree.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(treeTimeoutMs));

            expandTree();

            int checkboxes = page.getByRole(AriaRole.CHECKBOX).count();
            System.out.println("[INFO] Device tree is visible | selectable entries: " + checkboxes);

            if (checkboxes == 0) {
                System.err.println("[FAIL] Device tree rendered but contains no selectable devices.");
                return false;
            }

            System.out.println("[PASS] Device tree is populated.");
            return true;

        } catch (Exception exception) {
            System.err.println("[FAIL] Device tree did not become available: " + exception.getMessage());
            return false;
        }
    }

    /** Expands collapsed parent nodes until device checkboxes are reachable. */
    private void expandTree() {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (page.getByRole(AriaRole.CHECKBOX).count() > 0) {
                return;
            }
            Locator expanders = page.locator(
                    ".ph-v1-dynamic-tree-node__expand-circle, .MuiTreeItem-iconContainer, [aria-expanded='false']");
            int count = expanders.count();
            if (count == 0) {
                return;
            }
            boolean expanded = false;
            for (int index = 0; index < count; index++) {
                if (clickIfPresent(expanders.nth(index))) {
                    expanded = true;
                    page.waitForTimeout(700);
                }
            }
            if (!expanded) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------------
    // STEP 6 - active filter
    // ---------------------------------------------------------------------

    /**
     * Applies the "Active" device filter: opens the "Camera filters" panel, sets "Filter Status" to
     * Active via its native {@code <select>}, then closes the panel again.
     *
     * <p>Confirmed against the real Add Cameras dialog: the "Camera filters" trigger opens a panel
     * with three selects (Filter Hierarchy / Filter Status / Filter Tags); "Filter Status" is the one
     * with All/Active/Inactive. A missing filter is reported but not treated as a failure, since the
     * validation can still proceed across all devices.
     */
    private boolean applyActiveFilter() {
        if (openCameraFiltersPanel() && selectFilterStatusActive()) {
            closeCameraFiltersPanel();
            page.waitForTimeout(1000);
            System.out.println("[PASS] Active device filter applied via Filter Status.");
            return true;
        }
        closeCameraFiltersPanel();
        return applyActiveFilterFallback();
    }

    /** Clicks the visible "Camera filters" trigger and waits for its panel to render. */
    private boolean openCameraFiltersPanel() {
        Locator triggers = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Camera filters").setExact(true));
        int count = triggers.count();
        for (int index = 0; index < count; index++) {
            try {
                Locator trigger = triggers.nth(index);
                if (!trigger.isVisible()) {
                    continue;
                }
                trigger.click(new Locator.ClickOptions().setTimeout(5000));
                page.getByText("Filter Status", new Page.GetByTextOptions().setExact(true))
                        .waitFor(new Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(5000));
                return true;
            } catch (Exception ignored) {
                // The Archive page's own "Camera filters" button is obscured behind this dialog;
                // try the next match rather than the same hidden one.
            }
        }
        System.out.println("[INFO] No visible \"Camera filters\" trigger found.");
        return false;
    }

    /** Selects "Active" from the native select under the "Filter Status" label. */
    private boolean selectFilterStatusActive() {
        try {
            Locator select = page.getByText("Filter Status", new Page.GetByTextOptions().setExact(true))
                    .locator("xpath=following::select[1]");
            select.selectOption("Active");
            page.waitForTimeout(1000);
            String value = String.valueOf(select.evaluate("element => element.value"));
            return value.equalsIgnoreCase("active");
        } catch (Exception exception) {
            System.err.println("[WARN] Could not select Active from Filter Status: " + exception.getMessage());
            return false;
        }
    }

    /** Closes the filter panel opened by {@link #openCameraFiltersPanel()}; best-effort. */
    private void closeCameraFiltersPanel() {
        try {
            Locator close = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Close filter").setExact(true));
            if (close.count() > 0 && close.first().isVisible()) {
                close.first().click(new Locator.ClickOptions().setTimeout(3000));
                page.waitForTimeout(500);
            }
        } catch (Exception ignored) {
            // Not fatal: the dialog can still be used with the filter panel left open.
        }
    }

    /** Older, broader heuristic kept as a fallback for deployments without the panel above. */
    private boolean applyActiveFilterFallback() {
        java.util.regex.Pattern active =
                java.util.regex.Pattern.compile("^\\s*active\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);

        AriaRole[] roles = {AriaRole.TAB, AriaRole.BUTTON, AriaRole.RADIO, AriaRole.CHECKBOX,
                AriaRole.MENUITEM, AriaRole.OPTION};

        for (AriaRole role : roles) {
            try {
                Locator control = page.getByRole(role, new Page.GetByRoleOptions().setName(active)).first();
                if (control.count() > 0 && control.isVisible()) {
                    control.click(new Locator.ClickOptions().setTimeout(5000));
                    page.waitForTimeout(1500);
                    System.out.println("[PASS] Active device filter applied via " + role + " (fallback).");
                    return true;
                }
            } catch (Exception ignored) {
                // Try the next control type.
            }
        }

        System.out.println("[INFO] No Active filter control found; validating against all listed devices.");
        return false;
    }

    // ---------------------------------------------------------------------
    // STEP 7 + 8 - discovery and random selection
    // ---------------------------------------------------------------------

    /** Lists every selectable device with a usable name, read from the DOM rather than hard-coded. */
    private List<TreeDevice> discoverDevices() {
        List<TreeDevice> devices = new ArrayList<>();
        try {
            Locator checkboxes = page.getByRole(AriaRole.CHECKBOX);
            int count = checkboxes.count();

            for (int index = 0; index < count; index++) {
                Locator checkbox = checkboxes.nth(index);
                try {
                    if (!checkbox.isVisible() || checkbox.isChecked() || checkbox.isDisabled()) {
                        continue;
                    }
                } catch (Exception ignored) {
                    continue;
                }

                // The tree also carries a checkbox on each site/node folder (selects every camera
                // under it). Confirmed live: picking one saves fine but nothing ever reaches the
                // playback tile, since a folder is not a stream. Only real camera rows count.
                if (!isDeviceRow(index)) {
                    continue;
                }

                String name = deviceName(checkbox, index);
                devices.add(new TreeDevice(name, index));

                System.out.println("[ARCHIVE DEVICE]");
                System.out.println("Device: " + name);
                System.out.println("Status: DISCOVERED");
            }
        } catch (Exception exception) {
            System.err.println("[WARN] Device discovery error: " + exception.getMessage());
        }

        System.out.println("[INFO] Selectable devices discovered: " + devices.size());
        return devices;
    }

    /** Best-effort readable name for a tree checkbox, falling back to its index. */
    private String deviceName(Locator checkbox, int index) {
        try {
            String label = checkbox.getAttribute("aria-label");
            if (label != null && !label.isBlank()) {
                return label.trim();
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        try {
            // The nearest ancestor <div> is just an icon wrapper with no text; the label lives in
            // ".ph-v1-dynamic-tree-node__label" further up the same tree row. Reading the row's own
            // <li role="treeitem"> instead of the first ancestor div is what actually reaches it.
            String text = checkbox.locator(
                    "xpath=ancestor::*[@role='treeitem' or self::li][1]//*[contains(@class,'ph-v1-dynamic-tree-node__label')]")
                    .first().textContent();
            if (text != null && !text.isBlank()) {
                return text.trim().replaceAll("\\s+", " ");
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        try {
            // Generic fallback for deployments without that label class: the row's own text, minus
            // any nested child rows so a parent's name is never padded with its children's names.
            Object text = checkbox.evaluate(ROW_TEXT_SCRIPT);
            String value = text == null ? "" : text.toString().trim().replaceAll("\\s+", " ");
            if (!value.isBlank()) {
                return value;
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return "Device #" + index;
    }

    private TreeDevice selectRandomly(List<TreeDevice> devices) {
        long seed = resolveSeed();
        TreeDevice device = devices.get(new Random(seed).nextInt(devices.size()));

        System.out.println("[ARCHIVE RANDOM DEVICE]");
        System.out.println("Seed     : " + seed + "   (set archive.validation.random.seed to reproduce)");
        System.out.println("Selected : " + device.name() + " (checkbox index " + device.checkboxIndex() + ")");
        return device;
    }

    // ---------------------------------------------------------------------
    // STEP 8b - online-only selection
    // ---------------------------------------------------------------------

    /**
     * Random selection restricted to the devices the tree reports as online.
     *
     * <p>Additional to {@link #selectRandomly(List)}, which is left untouched: this only narrows the
     * candidate list and then delegates to it, so the seeded/reproducible behaviour is unchanged.
     * If nothing can be confirmed online the full list is used again, so a deployment whose tree
     * carries no status markers degrades to the previous behaviour instead of failing the soak.
     *
     * @return the chosen device, or {@code null} when {@code devices} is empty
     */
    private TreeDevice selectRandomOnlineDevice(List<TreeDevice> devices) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        if (!boolConfig("archive.online.only", true)) {
            return selectRandomly(devices);
        }

        List<TreeDevice> online = filterOnlineDevices(devices);
        System.out.println("[INFO] Online devices: " + online.size() + " of " + devices.size());

        if (online.isEmpty()) {
            System.out.println("[WARN] No device could be confirmed online; "
                    + "falling back to the full device list.");
            return selectRandomly(devices);
        }
        return selectRandomly(online);
    }

    /** Keeps only the devices whose own tree row does not advertise an offline state. */
    private List<TreeDevice> filterOnlineDevices(List<TreeDevice> devices) {
        List<TreeDevice> online = new ArrayList<>();
        for (TreeDevice device : devices) {
            if (isOnline(device)) {
                online.add(device);
            } else {
                System.out.println("[SKIP] Offline device: " + device.name()
                        + " (checkbox index " + device.checkboxIndex() + ")");
            }
        }
        return online;
    }

    /**
     * Status verdict for one tree row. Offline markers win over online markers, and a row that
     * advertises neither counts as online unless {@code archive.online.strict} is enabled.
     *
     * <p>Checks the device-status icon's own {@code aria-label} first (confirmed on the real tree:
     * {@code <div aria-label="Device is online">} sits next to the checkbox) before falling back to
     * the broader row-signature regex, which stays as the safety net for deployments that label the
     * icon differently.
     */
    private boolean isOnline(TreeDevice device) {
        boolean unknownIsOnline = !boolConfig("archive.online.strict", false);

        String iconLabel = statusIconLabel(device.checkboxIndex());
        if (!iconLabel.isBlank()) {
            if (iconLabel.contains("offline")) {
                return false;
            }
            if (iconLabel.contains("online")) {
                return true;
            }
        }

        String signature = statusSignature(device.checkboxIndex());
        if (signature.isBlank()) {
            return unknownIsOnline;
        }
        if (pattern("archive.offline.pattern", DEFAULT_OFFLINE_PATTERN).matcher(signature).find()) {
            return false;
        }
        if (pattern("archive.online.pattern", DEFAULT_ONLINE_PATTERN).matcher(signature).find()) {
            return true;
        }
        return unknownIsOnline;
    }

    /**
     * True when this tree row is an actual camera, not a site/node folder. Folder rows carry a
     * checkbox too (selects every camera under them) but neither the online/offline status icon nor
     * the device-icon image that every real camera row has.
     */
    private boolean isDeviceRow(int checkboxIndex) {
        try {
            Object isDevice = page.getByRole(AriaRole.CHECKBOX).nth(checkboxIndex)
                    .evaluate("el => {"
                            + "  const row = el.closest(\"li, [role='treeitem']\") || el.parentElement;"
                            + "  if (!row) return true;"
                            + "  return !!row.querySelector('[aria-label*=\"online\" i], [aria-label*=\"offline\" i],"
                            + " .ph-v1-dynamic-tree-node__device-icon');"
                            + "}");
            return !Boolean.FALSE.equals(isDevice);
        } catch (Exception exception) {
            // Fail open: a read error should not silently shrink the candidate pool.
            return true;
        }
    }

    /** {@code aria-label} of the row's own online/offline status icon (e.g. "Device is online"), if any. */
    private String statusIconLabel(int checkboxIndex) {
        try {
            Object label = page.getByRole(AriaRole.CHECKBOX).nth(checkboxIndex)
                    .evaluate("el => {"
                            + "  const row = el.closest(\"li, [role='treeitem']\") || el.parentElement;"
                            + "  const icon = row ? row.querySelector('[aria-label*=\"online\" i], [aria-label*=\"offline\" i]') : null;"
                            + "  return icon ? icon.getAttribute('aria-label') : '';"
                            + "}");
            return label == null ? "" : label.toString().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return "";
        }
    }

    /**
     * Markup of the device's own tree row - visible text plus classes, titles and data attributes,
     * which is where status dots hide - with nested child rows stripped so a parent node is never
     * judged by its children. Returns an empty string when the row cannot be read.
     */
    private String statusSignature(int checkboxIndex) {
        try {
            Object signature = page.getByRole(AriaRole.CHECKBOX).nth(checkboxIndex)
                    .evaluate(ROW_SIGNATURE_SCRIPT);
            return signature == null ? "" : signature.toString().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return "";
        }
    }

    /** Compiles a configurable, case-insensitive marker pattern, falling back on a bad override. */
    private java.util.regex.Pattern pattern(String key, String fallback) {
        String configured = ConfigReader.getOrDefault(key, fallback);
        String source = (configured == null || configured.isBlank()) ? fallback : configured.trim();
        try {
            return java.util.regex.Pattern.compile(source, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (Exception exception) {
            System.err.println("[WARN] Invalid pattern for " + key + "; using the default.");
            return java.util.regex.Pattern.compile(fallback, java.util.regex.Pattern.CASE_INSENSITIVE);
        }
    }

    private boolean boolConfig(String key, boolean fallback) {
        String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
        return (value == null || value.isBlank()) ? fallback : Boolean.parseBoolean(value.trim());
    }

    // ---------------------------------------------------------------------
    // STEP 9 + 10 + 11 - select, save, confirm
    // ---------------------------------------------------------------------

    private boolean selectDevice(TreeDevice device) {
        try {
            Locator checkbox = page.getByRole(AriaRole.CHECKBOX).nth(device.checkboxIndex());
            checkbox.scrollIntoViewIfNeeded();
            try {
                checkbox.check(new Locator.CheckOptions().setTimeout(10000));
            } catch (Exception exception) {
                // Custom checkbox widgets often need a plain click instead.
                checkbox.click(new Locator.ClickOptions().setTimeout(10000));
            }
            page.waitForTimeout(500);
            System.out.println("[PASS] Device selected: " + device.name());
            return true;
        } catch (Exception exception) {
            System.err.println("[FAIL] Could not select device " + device.name() + ": " + exception.getMessage());
            return false;
        }
    }

    /**
     * Commits the selection. Candidates are tried in priority order so the still-present "Add
     * camera" trigger is never mistaken for the dialog's confirm button.
     */
    private boolean saveSelection() {
        String[] candidates = {"save\\s+changes", "apply", "confirm", "^\\s*save\\s*$", "^\\s*add\\s*$"};

        for (String candidate : candidates) {
            try {
                Locator button = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                        .setName(java.util.regex.Pattern.compile(candidate, java.util.regex.Pattern.CASE_INSENSITIVE)))
                        .first();
                if (button.count() == 0 || !button.isVisible() || button.isDisabled()) {
                    continue;
                }
                button.click(new Locator.ClickOptions().setTimeout(10000));
                page.waitForTimeout(3000);
                System.out.println("[PASS] Selection saved via \"" + candidate + "\".");
                return true;
            } catch (Exception ignored) {
                // Try the next candidate.
            }
        }

        System.err.println("[FAIL] No Save changes / Apply button was found or clickable.");
        return false;
    }

    /**
     * Confirms the camera reached the playback area, by name where possible. Polls for up to
     * {@code archive.playback.startup.timeout.seconds} (default 60s) instead of checking once
     * immediately after Save: the tile can take a moment to mount and its backing API call to
     * resolve, and a single early check reads that gap as "not added" even though it lands a
     * second later - confirmed live, where the tile and a "Recording loaded" toast both appeared
     * just after this check had already failed.
     */
    private boolean validateCameraAdded(String deviceName) {
        long timeoutMs = Math.max(1, intConfig("archive.playback.startup.timeout.seconds",
                DEFAULT_STARTUP_TIMEOUT_SECONDS)) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        String shortName = deviceName.length() > 40 ? deviceName.substring(0, 40) : deviceName;

        while (true) {
            try {
                Locator byName = page.getByText(shortName, new Page.GetByTextOptions().setExact(false)).first();
                if (byName.count() > 0 && byName.isVisible()) {
                    System.out.println("[PASS] Camera is present in the playback area: " + shortName);
                    return true;
                }

                // Some builds render the tile without a text label; a media element is equally valid.
                Locator media = page.locator("video, canvas, [class*='player' i]").first();
                if (media.count() > 0 && media.isVisible()) {
                    System.out.println("[PASS] Playback tile rendered for the added camera.");
                    return true;
                }
            } catch (Exception ignored) {
                // Transient DOM read while the tile is still mounting; keep polling.
            }

            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            page.waitForTimeout(STARTUP_POLL_INTERVAL_MS);
        }

        System.err.println("[FAIL] Camera did not appear in the playback area within " + (timeoutMs / 1000) + "s.");
        return false;
    }

    // ---------------------------------------------------------------------
    // STEP 12 - recording playback
    // ---------------------------------------------------------------------

    /** Full media check: element, source, media error, readyState, resolution and real progress. */
    private void validatePlaybackStream(ArchiveReport report, String deviceName) {
        try {
            Locator video = page.locator("video").last();
            try {
                video.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(20000));
            } catch (Exception exception) {
                fail(report, "No playback video element became visible", slug(deviceName) + "-no-video");
                return;
            }
            report.videoElement = "PASS";

            String source = String.valueOf(video.evaluate("element => element.currentSrc || element.src || ''"));
            report.sourceValue = source == null || source.isBlank() ? "<empty>" : source;
            if (source == null || source.isBlank()) {
                report.videoSource = "FAIL";
                fail(report, "Playback video has no source", slug(deviceName) + "-no-source");
                return;
            }
            report.videoSource = "PASS";

            String mediaError = String.valueOf(video.evaluate(
                    "element => element.error ? JSON.stringify({code: element.error.code, message: element.error.message}) : ''"));
            if (mediaError != null && !mediaError.isBlank() && !"null".equals(mediaError)) {
                fail(report, "Media error: " + mediaError, slug(deviceName) + "-media-error");
                return;
            }

            video.evaluate("element => { element.muted = true; element.volume = 0; element.play(); }");

            if (!waitForStreamToStart(video, report, deviceName)) {
                return; // waitForStreamToStart() already recorded the failure.
            }

            monitorPlayback(video, report, deviceName);

        } catch (Exception exception) {
            fail(report, "Playback validation error: " + exception.getMessage(),
                    slug(deviceName) + "-playback-exception");
        }
    }

    /**
     * Polls the newly added stream for up to {@code archive.playback.startup.timeout.seconds}
     * (default 60s) until it actually has a decodable frame - {@code readyState >= 2} and a
     * non-zero resolution - instead of taking one snapshot shortly after {@code play()} and
     * failing on it. A stream is only declared failed once nothing has arrived by the deadline.
     *
     * @return {@code true} once the stream has started; {@code false} after recording a failure
     *         (element lost, a media error surfaced, or the deadline was reached with nothing)
     */
    private boolean waitForStreamToStart(Locator video, ArchiveReport report, String deviceName) {
        long timeoutMs = Math.max(1, intConfig("archive.playback.startup.timeout.seconds",
                DEFAULT_STARTUP_TIMEOUT_SECONDS)) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;

        int readyState = 0;
        int width = 0;
        int height = 0;

        System.out.println("[INFO] Waiting up to " + (timeoutMs / 1000)
                + "s for the newly added stream to start - device: " + deviceName);

        while (true) {
            if (video.count() == 0 || !video.isVisible()) {
                fail(report, "Playback video element disappeared while waiting for the stream to start",
                        slug(deviceName) + "-video-lost-startup");
                return false;
            }

            String mediaError = String.valueOf(video.evaluate(
                    "element => element.error ? JSON.stringify({code: element.error.code, message: element.error.message}) : ''"));
            if (mediaError != null && !mediaError.isBlank() && !"null".equals(mediaError)) {
                fail(report, "Media error while waiting for the stream to start: " + mediaError,
                        slug(deviceName) + "-startup-media-error");
                return false;
            }

            readyState = ((Number) video.evaluate("element => element.readyState")).intValue();
            width = ((Number) video.evaluate("element => element.videoWidth")).intValue();
            height = ((Number) video.evaluate("element => element.videoHeight")).intValue();
            report.readyState = readyState;
            report.resolution = width + "x" + height;

            if (readyState >= 2 && width > 0 && height > 0) {
                report.readyStateStatus = "PASS";
                System.out.println("[PASS] Stream started - readyState=" + readyState
                        + " resolution=" + report.resolution);
                return true;
            }

            if (System.currentTimeMillis() >= deadline) {
                break;
            }

            page.waitForTimeout(STARTUP_POLL_INTERVAL_MS);
        }

        report.readyStateStatus = "FAIL";
        fail(report, "Stream did not start within " + (timeoutMs / 1000) + "s (readyState=" + readyState
                + ", resolution=" + width + "x" + height + ")", slug(deviceName) + "-startup-timeout");
        return false;
    }

    /**
     * Watches {@code video} for {@code archive.playback.monitor.seconds} (default 30s), polling every
     * {@code archive.playback.monitor.interval.ms} (default 2s).
     *
     * <p>Replaces the single before/after snapshot this used to take: a camera that plays for three
     * seconds and then stalls looked identical to a healthy one under that check. Each sample re-checks
     * the element is still there, carries no media error, and that {@code currentTime} is still moving;
     * {@code archive.playback.monitor.stall.samples} consecutive non-progressing samples end the check
     * early as a stall rather than waiting out the full window.
     */
    private void monitorPlayback(Locator video, ArchiveReport report, String deviceName) {
        long monitorMs = Math.max(1, intConfig("archive.playback.monitor.seconds", DEFAULT_MONITOR_SECONDS)) * 1000L;
        long intervalMs = Math.max(500, intConfig("archive.playback.monitor.interval.ms", DEFAULT_MONITOR_INTERVAL_MS));
        int maxStallSamples = Math.max(1, intConfig("archive.playback.monitor.stall.samples", DEFAULT_MONITOR_STALL_SAMPLES));

        double startTime = ((Number) video.evaluate("element => element.currentTime")).doubleValue();
        double lastTime = startTime;
        int stallStreak = 0;
        int samples = 0;

        System.out.println("[INFO] Monitoring playback for " + (monitorMs / 1000)
                + "s (interval " + intervalMs + "ms) - device: " + deviceName);

        long deadline = System.currentTimeMillis() + monitorMs;
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(intervalMs);
            samples++;

            if (video.count() == 0 || !video.isVisible()) {
                fail(report, "Playback video element became unavailable during monitoring (sample " + samples + ")",
                        slug(deviceName) + "-video-lost");
                return;
            }

            String sampleError = String.valueOf(video.evaluate(
                    "element => element.error ? JSON.stringify({code: element.error.code, message: element.error.message}) : ''"));
            if (sampleError != null && !sampleError.isBlank() && !"null".equals(sampleError)) {
                fail(report, "Media error during monitoring (sample " + samples + "): " + sampleError,
                        slug(deviceName) + "-monitor-media-error");
                return;
            }

            boolean sampleEnded = (Boolean) video.evaluate("element => element.ended");
            boolean samplePaused = (Boolean) video.evaluate("element => element.paused");
            report.readyState = ((Number) video.evaluate("element => element.readyState")).intValue();
            double sampleTime = ((Number) video.evaluate("element => element.currentTime")).doubleValue();
            boolean sampleProgressed = sampleTime > lastTime + PLAYBACK_TOLERANCE_SECONDS;

            System.out.println("[MONITOR] sample=" + samples + "/" + (monitorMs / intervalMs)
                    + " currentTime=" + sampleTime + " readyState=" + report.readyState
                    + " paused=" + samplePaused + " progressed=" + sampleProgressed);

            if (sampleEnded) {
                fail(report, "Recording ended before the " + (monitorMs / 1000) + "s monitoring window completed"
                        + " (sample " + samples + ")", slug(deviceName) + "-ended-early");
                return;
            }

            if (samplePaused || report.readyState < 2 || !sampleProgressed) {
                stallStreak++;
                if (stallStreak >= maxStallSamples) {
                    fail(report, "Playback stalled: no progress for " + (stallStreak * intervalMs / 1000)
                            + "s (paused=" + samplePaused + ", readyState=" + report.readyState + ")",
                            slug(deviceName) + "-stalled");
                    return;
                }
            } else {
                stallStreak = 0;
            }

            lastTime = sampleTime;
        }

        double totalProgressed = lastTime - startTime;
        if (totalProgressed <= PLAYBACK_TOLERANCE_SECONDS) {
            report.playback = "FAIL";
            fail(report, "Recording did not progress over the " + (monitorMs / 1000) + "s monitoring window"
                    + " (currentTime " + startTime + " -> " + lastTime + ")", slug(deviceName) + "-no-progress");
            return;
        }

        report.playback = "PASS";
        report.passed = true;
        System.out.println("[PASS] Playback progressed continuously for " + (monitorMs / 1000) + "s"
                + " (currentTime " + startTime + " -> " + lastTime + ", " + samples + " sample(s)).");
    }

    // ---------------------------------------------------------------------
    // API correlation (reporting only; ApiMonitor owns the consolidated log)
    // ---------------------------------------------------------------------

    private void reportCorrelatedApiFailures(String deviceName, int failuresBefore) {
        try {
            List<ApiMonitor.ApiFailure> failures = ApiMonitor.getFailures();
            if (failures.size() <= failuresBefore) {
                return;
            }
            for (ApiMonitor.ApiFailure failure : failures.subList(failuresBefore, failures.size())) {
                System.err.println(SEPARATOR);
                System.err.println("ARCHIVE CAMERA API FAILURE");
                System.err.println(SEPARATOR);
                System.err.println("Camera       : " + deviceName);
                System.err.println("HTTP Status  : " + failure.status() + " " + failure.statusText());
                System.err.println("Method       : " + failure.method());
                System.err.println("URL          : " + failure.url());
                System.err.println("Time         : " + failure.timestamp());
                System.err.println(SEPARATOR);
            }
        } catch (Exception exception) {
            System.err.println("[WARN] Could not correlate API failures: " + exception.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Reporting helpers
    // ---------------------------------------------------------------------

    private void fail(ArchiveReport report, String reason, String screenshotName) {
        report.passed = false;
        report.failureReason = reason;
        report.screenshot = captureScreenshot(screenshotName);

        SoakReporter.recordStreamFailure("Archive", report.selectedDevice,
                "PASS".equals(report.videoElement) ? "FOUND" : "NOT FOUND",
                "PASS".equals(report.cameraAdded) ? "YES" : "NO",
                "PASS".equals(report.videoSource) ? "PRESENT" : "MISSING",
                report.readyState, report.resolution, !"PASS".equals(report.playback), 0,
                reason, report.screenshot);

        System.err.println(SEPARATOR);
        System.err.println("ARCHIVE CAMERA FAILURE");
        System.err.println(SEPARATOR);
        System.err.println("Device        : " + report.selectedDevice);
        System.err.println("Failure       : " + reason);
        System.err.println("Video Source  : " + report.sourceValue);
        System.err.println("Ready State   : " + report.readyState);
        System.err.println("Resolution    : " + report.resolution);
        System.err.println("Screenshot    : " + (report.screenshot == null ? "<not captured>" : report.screenshot));
        System.err.println(SEPARATOR);
    }

    private boolean finish(ArchiveReport report) {
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("ARCHIVE / PLAYBACK VALIDATION");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("Archive Page       : " + report.archivePage);
        System.out.println("Archive Data       : " + report.archiveData);
        System.out.println("Add Camera Dialog  : " + report.addCameraDialog);
        System.out.println("Device Tree        : " + report.deviceTree);
        System.out.println("Active Filter      : " + report.activeFilter);
        System.out.println("Devices Found      : " + report.devicesFound);
        System.out.println();
        System.out.println(SUB_SEPARATOR);
        System.out.println("SELECTED CAMERA");
        System.out.println(SUB_SEPARATOR);
        System.out.println();
        System.out.println("Device             : " + report.selectedDevice);
        System.out.println("Checkbox Selected  : " + report.checkboxSelected);
        System.out.println("Save Changes       : " + report.saveChanges);
        System.out.println("Camera Added       : " + report.cameraAdded);
        System.out.println("Video Element      : " + report.videoElement);
        System.out.println("Video Source       : " + report.videoSource);
        System.out.println("Ready State        : " + report.readyStateStatus);
        System.out.println("Resolution         : " + report.resolution);
        System.out.println("Playback           : " + report.playback);
        System.out.println("Stream             : " + (report.passed ? "PASS" : "FAIL"));
        if (!report.passed && report.failureReason != null) {
            System.out.println("Failure            : " + report.failureReason);
        }
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("ARCHIVE VALIDATION RESULT: " + (report.passed ? "PASS" : "FAIL"));
        System.out.println(SEPARATOR);
        return report.passed;
    }

    private String captureScreenshot(String name) {
        return ScreenshotUtils.captureTo(page, screenshotDirectory, name);
    }

    private boolean clickIfPresent(Locator locator) {
        try {
            if (locator.count() == 0 || !locator.isVisible()) {
                return false;
            }
            locator.click(new Locator.ClickOptions().setTimeout(3000));
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private long resolveSeed() {
        String configured = ConfigReader.getOrDefault("archive.validation.random.seed", "");
        if (configured != null && !configured.isBlank()) {
            try {
                return Long.parseLong(configured.trim());
            } catch (NumberFormatException ignored) {
                // Fall through to a fresh seed.
            }
        }
        return System.nanoTime();
    }

    private int intConfig(String key, int fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String slug(String value) {
        if (value == null || value.isBlank()) {
            return "device";
        }
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return slug.isBlank() ? "device" : slug;
    }
}
