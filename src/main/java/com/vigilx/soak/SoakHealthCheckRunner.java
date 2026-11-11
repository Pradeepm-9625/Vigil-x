package com.vigilx.soak;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.ScreenshotType;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.monitoring.ApiMonitor;
import com.vigilx.monitoring.LiveViewMonitor;
import com.vigilx.monitoring.PageApiTracker;
import com.vigilx.apisecurity.performance.SoakApiJmxGenerator;
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.ApplicationSettingsPage;
import com.vigilx.pages.ArchiveExportValidation;
import com.vigilx.pages.AuditLogsPage;
import com.vigilx.pages.ConfigurationPage;
import com.vigilx.pages.DeviceDetailsValidation;
import com.vigilx.pages.EventAcknowledgementPage;
import com.vigilx.pages.EventSearchValidation;
import com.vigilx.pages.InfraPage;
import com.vigilx.pages.GroupData;
import com.vigilx.pages.GroupsPage;
import com.vigilx.pages.LicensePage;
import com.vigilx.pages.LiveViewCrudPage;
import com.vigilx.pages.MasterConfigurationValidation;
import com.vigilx.pages.MasterDataPage;
import com.vigilx.pages.NotificationRulesPage;
import com.vigilx.pages.OrganizationData;
import com.vigilx.pages.OrganizationPage;
import com.vigilx.pages.ProjectInformationData;
import com.vigilx.pages.ProjectInformationPage;
import com.vigilx.pages.QCItemData;
import com.vigilx.pages.QCPage;
import com.vigilx.pages.QCSectionData;
import com.vigilx.pages.RoleData;
import com.vigilx.pages.RolesPage;
import com.vigilx.pages.SequencePage;
import com.vigilx.pages.UserData;
import com.vigilx.pages.UsersRolesPage;
import com.vigilx.pages.VmsOperatorSidebarPage;
import com.vigilx.pages.ProjectHierarchyValidation;
import com.vigilx.pages.ReportsValidation;
import com.vigilx.reporting.SoakConsolidatedReportGenerator;
import com.vigilx.reporting.SoakReporter;
import com.vigilx.reporting.SoakRunContext;
import com.vigilx.pages.DashboardPage;
import com.vigilx.pages.LoginPage;
import com.vigilx.utils.LoggerUtils;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.WaitUtils;

/** Runs one health check per fresh browser, or repeats it until the configured duration ends. */
public final class SoakHealthCheckRunner {
    private static final Logger LOG = LoggerUtils.getLogger(SoakHealthCheckRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS");
    /** Soak cycle number, stamped onto every API failure so repeats across cycles stay distinguishable. */
    private static final java.util.concurrent.atomic.AtomicInteger ITERATION =
            new java.util.concurrent.atomic.AtomicInteger();
    private SoakHealthCheckRunner() { }

    /**
     * Marks "every configured validation ran and the application was found unhealthy" - as opposed to
     * a genuine infrastructure/framework problem. Thrown only by the two checks at the end of the try
     * block below, after every validation, logout and browser cleanup has already completed; the
     * catch block uses the type (not the message) to tell the two apart, so {@link SoakResult#overall}
     * still ends up "FAIL" exactly as before while {@link SoakResult#executionStatus} stays
     * "COMPLETED" instead of being misreported as an execution error.
     */
    private static final class SoakValidationFailedException extends RuntimeException {
        SoakValidationFailedException(String message) {
            super(message);
        }
    }

    public static SoakResult runOnce() {
        SoakTestConfig config = SoakTestConfig.load();
        int iteration = ITERATION.incrementAndGet();
        ApiMonitor.setIteration(iteration);
        // Fresh timestamped evidence folder; earlier runs are never deleted.
        SoakRunContext.start();
        SoakResult result = new SoakResult();
        result.executionId = "SOAK-" + LocalDateTime.now().format(ID);
        result.camera = config.cameraName();
        long started = System.nanoTime();
        Path evidence = Path.of(config.outputDirectory(), result.executionId);
        Page page = null;
        BrowserContext context = null;
        boolean tracing = false;
        try {
            // No eager "screenshots" directory here: it is created lazily, only at the moment a
            // genuine failure screenshot is actually captured (see captureFailureScreenshot() and
            // the two direct page.screenshot() call sites below) - an all-PASS run must never leave
            // behind an empty screenshots/ folder. "trace" is unrelated (always written for a
            // completed run, pass or fail) and is left exactly as it was.
            Files.createDirectories(evidence.resolve("trace"));
            page = PlaywrightFactory.initializeBrowser();
            page.setDefaultTimeout(ConfigReader.getInt("soak.page.timeout.ms"));
            page.onResponse(response -> {
                int status = response.status();
                if (status == 500 || status == 502) {
                    result.apiFailures.add(response.request().method() + " " + status + " " + response.url());
                }
            });
            context = PlaywrightFactory.getContext();
            page.navigate(ConfigReader.get("base.url"));
            WaitUtils.waitAfterPageNavigation(page);
            context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));
            tracing = true;
            LOG.info("[SOAK] {} started", result.executionId);
            DashboardPage dashboard = new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            result.login = "PASS";
            LOG.info("[SOAK] Login completed | status={}", result.login);
            if (!dashboard.isDashboardLoaded()) throw new IllegalStateException("Dashboard URL was not reached after login");
            result.dashboard = "PASS";
            LOG.info("[SOAK] Dashboard validation completed | status={}", result.dashboard);

            // Dashboard Tabs: the Dashboard's own "Alerts"/"Device"/"Infra" tabs (siblings of the
            // default "Overview"), confirming each one's content panel genuinely switches - not just
            // that the tab itself gets selected. Runs after Dashboard validation above, before
            // Project Hierarchy - never modifying either. Additive and config-gated (the flag already
            // existed in SoakTestConfig, unused until now).
            if (config.dashboardTabsEnabled()) {
                validatePage(page, result, "Dashboard Tabs", dashboard::validateDashboardTabs);
            }

            ApplicationHealthPage healthPages = new ApplicationHealthPage(page);
            String appUrl = ConfigReader.get("base.url").replace("/onboarding", "");

            validatePage(page, result, "Project Hierarchy", healthPages::validateProjectHierarchy);
            // Project Hierarchy deep validation: random Node + Site edit/save, then its own Audit
            // Logs search + export sub-flow. Runs after the existing Project Hierarchy check above
            // (untouched), before Devices. Config-gated; additive; independent of
            // ApplicationHealthPage.
            if (config.projectHierarchyEnabled()) {
                ProjectHierarchyValidation projectHierarchy = new ProjectHierarchyValidation(page);
                if (projectHierarchy.open()) {
                    validatePage(page, result, "Project Hierarchy - Node & Site Edit",
                            projectHierarchy::validateRandomNodeAndSite);
                    validatePage(page, result, "Project Hierarchy - Audit Logs",
                            projectHierarchy::validateAuditLogs);
                }
            }

            // Device Creation: Devices -> Add Devices -> Add Device Manually -> connection details ->
            // Test Connection -> onboarding details -> Save changes. Runs right after Dashboard,
            // before the existing Devices / Device Tabs / Device Details checks below, none of which
            // are modified - it only navigates to the Devices list and leaves it exactly where those
            // checks already expect to find it. Config-gated; additive.
            // Captured only when Device Creation below truly succeeds - every later step that reads
            // this (targeting Device Details, then Decommission) is gated on it being non-blank, so
            // nothing here can ever act on an arbitrary/pre-existing device.
            String createdDeviceName = "";
            if (Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.device.creation", "true"))) {
                DeviceDetailsValidation deviceCreation = new DeviceDetailsValidation(page);
                validatePage(page, result, "Device Creation", () -> deviceCreation.createDevice(appUrl));
                // Point the existing Device Details targeting knob (device.details.device.text,
                // already read by DeviceDetailsValidation.open() - unmodified) at the device just
                // onboarded, so the existing Devices/Device Tabs/Device Details checks below open
                // and validate THAT device rather than an arbitrary Online row. Only when creation
                // truly succeeded and a name was captured; otherwise the checks below keep their
                // existing behavior untouched.
                if ("PASS".equals(result.pageResults.get("Device Creation"))
                        && !deviceCreation.lastCreatedDeviceName().isBlank()) {
                    createdDeviceName = deviceCreation.lastCreatedDeviceName();
                    System.setProperty("device.details.device.text", createdDeviceName);
                    LOG.info("[SOAK] Device Details will target the newly onboarded device: {}", createdDeviceName);
                }
            }

            validatePage(page, result, "Devices", healthPages::validateDevices);
            validatePage(page, result, "Device Tabs", healthPages::validateDeviceTabs);
            // Deep Device Details validation: open one device and walk every tab (Details,
            // Streams, Recordings, VA Settings, Health, Audit Logs) plus the page controls. Runs
            // after the existing Device Tabs check above (untouched), reusing the device page it
            // leaves open. Config-gated; additive.
            if (config.deviceDetailsEnabled()) {
                DeviceDetailsValidation deviceDetails = new DeviceDetailsValidation(page);
                if (deviceDetails.open(appUrl)) {
                    for (String section : DeviceDetailsValidation.FLOW) {
                        validatePage(page, result, "Device Details - " + section,
                                () -> deviceDetails.validateSection(section));
                    }
                }
            }

            // Device Decommission: cleans up the device Device Creation onboarded above, once every
            // Device Details tab check above has run, before Master Configuration begins. Gated
            // strictly on createdDeviceName being non-blank (only ever set when Device Creation
            // truly succeeded this run) - this can never decommission an arbitrary/pre-existing
            // device. Config-gated (same flag as Device Creation); additive.
            if (!createdDeviceName.isBlank()) {
                DeviceDetailsValidation deviceDecommission = new DeviceDetailsValidation(page);
                final String deviceNameToDecommission = createdDeviceName;
                validatePage(page, result, "Device Decommission",
                        () -> deviceDecommission.decommissionDevice(appUrl, deviceNameToDecommission));
            }

            // Master Configuration: create/rename/remove + tab configuration. Runs after Device
            // Details, before License. Config-gated; additive.
            if (config.masterConfigurationEnabled()) {
                MasterConfigurationValidation masterConfiguration = new MasterConfigurationValidation(page);
                validatePage(page, result, "Master Configuration",
                        masterConfiguration::validateMasterConfiguration);

                // Audit Logs (post-Master Configuration): the "Devices" left-nav group's own
                // "Audit Logs" link (a sibling of "Master Configuration"), landing on
                // /devices/audit-logs - reuses AuditLogsPage's existing clearDateTimeFilter()/
                // exportAuditLogs()/verifyDownloadedFile() as-is. Runs only after Master
                // Configuration completes, before Alerts. Same config flag - no new flag needed.
                AuditLogsPage devicesAuditLogs = new AuditLogsPage(page);
                validatePage(page, result, "Audit Logs - Master Configuration",
                        devicesAuditLogs::runDevicesAuditLogsExportFlow);
            }

            if (config.alertEnabled()) {
                Path alertScreenshot = evidence.resolve("screenshots/video-alert-failure.png");
                LOG.info("[SOAK] Alerts V1 and Video Alert validation started");
                // Unlike every other check in this method, this one is not routed through
                // validatePage() (it needs its own WARN-vs-FAIL semantics for
                // soak.alert.required=false, which validatePage()'s PASS/FAIL status does not
                // have), so it has no try/catch of its own - confirmed to be a real bug: an
                // uncaught RuntimeException here (validatePage() catches exactly this on every
                // other page) would propagate out of this whole method's try block and abort the
                // ENTIRE remaining run, silently skipping Reports, Organisation and everything
                // after - not a page-level FAIL, a total stop. Caught locally instead so a broken
                // Alerts check costs only its own result, never the rest of the soak.
                boolean alertPassed;
                String alertException = null;
                try {
                    alertPassed = healthPages.validateAlerts(appUrl, alertScreenshot);
                } catch (RuntimeException exception) {
                    alertPassed = false;
                    alertException = exception.getMessage();
                    LOG.error("[SOAK] Alerts V1 and Video Alert validation threw: {}", exception.getMessage(),
                            exception);
                }
                // soak.alert.required=false was never honoured: a video alert that carries only
                // images still failed the whole run. The status must not start with FAIL, since
                // that is what marks the run failed further down.
                String alertStatus = alertPassed ? "PASS"
                        : config.alertRequired() ? "FAIL" + (alertException != null ? ": " + alertException : "")
                                : "WARN: Video Alert validation failed (soak.alert.required=false)";
                result.alerts = alertPassed ? "PASS" : config.alertRequired() ? "FAIL" : "WARN";
                result.pageResults.put("Alerts V1 and Video Alert", alertStatus);
                LOG.info("[SOAK] Alerts V1 and Video Alert validation completed | status={}", alertStatus);
                if (!alertPassed) {
                    result.screenshot = "screenshots/video-alert-failure.png";
                    if (config.alertRequired()) {
                        result.error = appendError(result.error, "Video Alert validation failed");
                    }
                }
            }

            // Reports: page load, search + export validation. Runs after Alerts, before License.
            // Config-gated; additive.
            if (config.reportsEnabled()) {
                ReportsValidation reports = new ReportsValidation(page);
                validatePage(page, result, "Reports", reports::validateReports);
            }

            validatePage(page, result, "License", healthPages::validateSettings);

            // Application Settings (Infra Management -> Application Settings -> Device: "Enable QC" /
            // "Enable VA Alerts" / Notification Rules) used to run right here, immediately after
            // "Open settings" - confirmed live via a real recording to be a real bug: it navigated
            // straight to Infra Management before Organisation ever got a chance to run, instead of
            // the recorded page-by-page order (Settings -> Organisation -> ... -> About -> Infra
            // Management). It now runs at the very end of this whole Settings walk, right alongside
            // Infra Management's own NAS/SAN/Cloud/FTP buttons - see "Infra Management" below,
            // after Master Data - About.

            validatePage(page, result, "Organisation", healthPages::validateOrganisation);
            // Settings -> Organisation -> Profile: uploads a new organization logo (guaranteeing no
            // leftover crop window - including the native OS file-picker a headed browser would
            // otherwise pop open), then updates Admin Details / Primary Contact (once saved
            // unchanged, once with a new Last Name) / Address Details, verifying each save's toast
            // and the value actually shown afterward. Runs right after the existing Organisation
            // check above (which lands on this same page and is left untouched) and reuses the
            // existing OrganizationPage class rather than creating a new one. Additive and
            // config-gated. Runs before Users & Roles now (reordered to match the recorded
            // page-by-page walk: Settings -> Organisation -> Users & Roles -> Configuration ->
            // Master Data -> Infra Management) - the Users & Roles block below is otherwise
            // untouched.
            if (config.organisationLogoEnabled()) {
                OrganizationPage organisation = new OrganizationPage(page);
                OrganizationData organizationData = new OrganizationData(
                        ConfigReader.getOrDefault("organisation.logo.path", "src/test/resources/Logo.jpg"),
                        ConfigReader.getOrDefault("organisation.admin.last.name", "kumar"),
                        ConfigReader.getOrDefault("organisation.primary.contact.last.name", "Kumar update"),
                        ConfigReader.getOrDefault("organisation.address.line1",
                                "1 Railway Station Road, Madhira Bazar, Hyderabad, Telangana."),
                        ConfigReader.getOrDefault("organisation.address.line2",
                                "1 Station Road, Madhira Bazar 11, Hyderabad."));
                validatePage(page, result, "Organisation - Update Details",
                        () -> organisation.runOrganizationUpdateFlow(organizationData));

                // Settings -> Organisation -> Project Information: a sibling tab of the Profile tab
                // above - updates the project logo (same native-file-chooser-safe upload), then
                // updates the Description field (twice), Project Admin Details' Last Name, and
                // Address Details' Address Line 1. Runs right after the Organisation update above;
                // a dedicated ProjectInformationPage class, independent of OrganizationPage.
                if (config.projectInformationEnabled()) {
                    ProjectInformationPage projectInformation = new ProjectInformationPage(page);
                    ProjectInformationData projectInformationData = new ProjectInformationData(
                            organizationData.logoPath(),
                            ConfigReader.getOrDefault("project.information.description",
                                    "Detect events in real time, integrate with other city systems, and route.a"),
                            ConfigReader.getOrDefault("project.information.description.final",
                                    "Detect events in real time, integrate with other city systems, and route. Update"),
                            ConfigReader.getOrDefault("project.information.admin.last.name", "kumar - Update"),
                            ConfigReader.getOrDefault("project.information.address.line1",
                                    "Railway Station Road, Madhira Bazar, Hyderabad, Telangana. - Update"));
                    validatePage(page, result, "Organisation - Project Information",
                            () -> projectInformation.runProjectInformationFlow(projectInformationData));
                }

                // Settings -> Organisation -> License: opens "Activate New License", validates the
                // background license-lookup API(s) it triggers, verifies the dialog's own content,
                // and closes it again - never actually submits a license, per the requirement not
                // to invent activation data. Runs right after Project Information above.
                if (config.licenseValidationEnabled()) {
                    LicensePage license = new LicensePage(page);
                    validatePage(page, result, "Organisation - License", license::runLicenseValidationFlow);

                    // Settings -> Organisation -> Audit Logs: this is the SAME AuditLogsPage class
                    // built for Users & Roles -> Audit Logs earlier - its navigateToAuditLogs()
                    // already works off any visible "Audit Logs" tab generically, so reusing it
                    // here (a second, independent invocation) targets Organisation's own sibling
                    // Audit Logs tab (next to License / Project Information) with no new code. The
                    // "License" section is additionally selected within its own "Audit log
                    // sections" tablist, confirmed live via a real recording.
                    AuditLogsPage organisationAuditLogs = new AuditLogsPage(page);
                    validatePage(page, result, "Organisation - Audit Logs Export",
                            () -> organisationAuditLogs.runAuditLogsExportFlow("License"));
                }
            }

            // Settings -> Users & Roles -> Users -> Add User -> full user lifecycle: creates one
            // user, verifies it, then runs its lifecycle (inactivate/activate/edit/delete). Runs
            // after Organisation above, before Configuration / Master Data / Infra Management.
            // Config-gated; additive.
            validatePage(page, result, "Users & Roles", healthPages::validateUsersAndRoles);
            // Settings -> Users & Roles -> User Hierarchy: this tab has no functionality of its own
            // yet - navigation and page-load validation only, confirmed live via a real recording.
            // Runs before the Create User lifecycle below, matching the recorded page-by-page order.
            UsersRolesPage usersRolesNav = new UsersRolesPage(page);
            validatePage(page, result, "Users & Roles - User Hierarchy",
                    () -> usersRolesNav.navigateToUsersRoles() && usersRolesNav.navigateToUserHierarchyTab());
            if (config.userCreationEnabled()) {
                UsersRolesPage usersRoles = new UsersRolesPage(page);
                // Only the number after "+" in the configured email is replaced with a per-run unique
                // value (6 digits of epoch seconds); an email without a "+tag" is used unchanged.
                String userEmail = ConfigReader.getOrDefault("user.creation.email", "pradeep.m+50@solutionchamps.com")
                        .replaceFirst("\\+[^@]*@", java.util.regex.Matcher.quoteReplacement("+"
                                + String.format("%06d", (System.currentTimeMillis() / 1000) % 1_000_000) + "@"));
                UserData newUser = new UserData(
                        ConfigReader.getOrDefault("user.creation.first.name", "Pradeep"),
                        ConfigReader.getOrDefault("user.creation.last.name", "Testing"),
                        userEmail,
                        ConfigReader.getOrDefault("user.creation.mobile", "99999999999"),
                        ConfigReader.getOrDefault("user.creation.role", "Quality Check"),
                        ConfigReader.getOrDefault("user.creation.group", "Test"),
                        ConfigReader.getOrDefault("user.creation.profile.picture", "src/test/resources/Logo.jpg"));
                UserData updatedUser = new UserData(
                        newUser.firstName() + ConfigReader.getOrDefault("user.update.first.name.suffix", "update"),
                        newUser.lastName(), newUser.email(), newUser.mobileNumber(), newUser.role(),
                        newUser.group(), newUser.profilePicturePath());
                validatePage(page, result, "Users & Roles - Create User",
                        () -> usersRoles.createUserEndToEnd(newUser));
                validatePage(page, result, "Users & Roles - User Lifecycle",
                        () -> usersRoles.runUserLifecycle(newUser, updatedUser));

                // Settings -> Users & Roles -> Roles -> Custom Roles -> full role lifecycle: creates
                // one Custom Role (name + "Full" permission), verifies it, renames it, verifies the
                // rename, deletes it, and verifies it is gone. Runs after the User lifecycle above,
                // before the existing Users & Roles / Organisation checks (untouched). Additive and
                // config-gated; independent of UsersRolesPage, which it does not modify.
                if (config.roleCreationEnabled()) {
                    RolesPage roles = new RolesPage(page);
                    // Unique per run (config value + timestamp), same convention as Group creation
                    // below: a static name collides with the role left by an earlier run and makes
                    // the create fail as a duplicate. Base capped to 13 characters so base + space +
                    // 6-digit suffix + the "-update" rename suffix all still fit a 30-character field.
                    String roleMillis = String.format("%06d", (System.currentTimeMillis() / 1000) % 1_000_000);
                    String roleBase = ConfigReader.getOrDefault("role.creation.name", "Testing role");
                    if (roleBase.length() > 13) {
                        roleBase = roleBase.substring(0, 13).trim();
                    }
                    String uniqueRoleName = roleBase + " " + roleMillis.substring(roleMillis.length() - 6);
                    RoleData newRole = new RoleData(uniqueRoleName, true);
                    RoleData updatedRole = new RoleData(
                            uniqueRoleName + ConfigReader.getOrDefault("role.update.name.suffix", "-update"),
                            true);
                    validatePage(page, result, "Users & Roles - Role Lifecycle",
                            () -> roles.runRoleLifecycle(newRole, updatedRole));
                }
                // Settings -> Users & Roles -> Groups: creates one custom group (name + Email/Whatsapp/
                // SMS notifications), verifies it is listed, renames it, verifies the rename, deletes
                // it, and verifies it is gone - validating each operation's background API and success
                // toast. Runs after the Role lifecycle above and before the existing Users & Roles /
                // Organisation checks, which are left untouched. Additive and config-gated; independent
                // of UsersRolesPage and RolesPage, neither of which it modifies.
                if (config.groupCreationEnabled()) {
                    GroupsPage groups = new GroupsPage(page);
                    // Unique per run (config value + timestamp): a static name collides with the group
                    // left by an earlier run and makes the create fail as a duplicate.
                    // The field allows 30 characters and the rename appends " update", so the created name
                    // is capped at 20: base (trimmed to fit) + space + 6 digits of epoch seconds
                    // (repeats only every ~11.5 days, versus ~17 minutes for the last 6 millis digits).
                    String millis = String.format("%06d", (System.currentTimeMillis() / 1000) % 1_000_000);
                    String groupBase = ConfigReader.getOrDefault("group.creation.name", "Testing group");
                    if (groupBase.length() > 13) {
                        groupBase = groupBase.substring(0, 13).trim();
                    }
                    String uniqueGroupName = groupBase + " " + millis.substring(millis.length() - 6);
                    GroupData newGroup = new GroupData(uniqueGroupName, true, true, true);
                    GroupData updatedGroup = new GroupData(
                            // The separating space is added here in code, not carried by the config
                            // value - confirmed live: ConfigReader.getOrDefault() trims its result,
                            // which silently discards a leading space even when the properties file
                            // escapes it, so a config-carried " update" always collapsed back to
                            // "update" and produced "Testing groupupdate" with no space.
                            uniqueGroupName + " "
                                    + ConfigReader.getOrDefault("group.update.name.suffix", "update"),
                            true, true, true);
                    validatePage(page, result, "Users & Roles - Group Lifecycle",
                            () -> groups.runGroupLifecycle(newGroup, updatedGroup));
                }
                // Settings -> Users & Roles -> Audit Logs: opens the tab, clears the "Date & Time"
                // filter and confirms it is actually cleared, verifies the page's stable controls,
                // then exports and validates whatever comes back - a real file, or (confirmed live
                // against this build) the export API's own 404 when no file arrives. Runs after the
                // Group lifecycle above and before the existing Users & Roles / Organisation checks,
                // which are left untouched. Additive and config-gated; independent of UsersRolesPage,
                // RolesPage and GroupsPage, none of which it modifies.
                if (config.auditLogsExportEnabled()) {
                    AuditLogsPage auditLogs = new AuditLogsPage(page);
                    validatePage(page, result, "Users & Roles - Audit Logs Export",
                            auditLogs::runAuditLogsExportFlow);
                }
            }
            // Settings -> Configuration: its default landing view is the exact same "Enable QC" /
            // "Enable VA Alerts" feature already implemented in ApplicationSettingsPage (reached
            // there via Infra Management -> Application Settings -> Device) - confirmed live via a
            // real recording, so this reuses that existing implementation directly instead of a new,
            // duplicate one. The E-mail / Health / Notification Rules / Scheduled Jobs / System
            // Settings / Default Settings tabs that follow have no functional test of their own on
            // this screen, so each of those is navigation and page-load validation only. Runs after
            // Users & Roles, before Master Data. Config-gated; additive.
            if (Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.configuration", "true"))) {
                ConfigurationPage configurationPage = new ConfigurationPage(page);
                if (configurationPage.open()) {
                    validatePage(page, result, "Configuration - Application Settings",
                            configurationPage::exerciseApplicationSettings);
                    validatePage(page, result, "Configuration - Health", configurationPage::exerciseHealthTab);
                    validatePage(page, result, "Configuration - Notification Rules",
                            configurationPage::exerciseNotificationRulesTab);
                    validatePage(page, result, "Configuration - System Settings",
                            configurationPage::exerciseSystemSettingsTab);
                    validatePage(page, result, "Configuration - Default Settings",
                            configurationPage::exerciseDefaultSettingsTab);
                    validatePage(page, result, "Configuration - Tabs", configurationPage::visitAllTabs);
                }
            }

            // Settings -> Master Data -> Master Data: confirmed live via a real recording that this
            // checklist section/item CRUD (previously reached via a separate "QC" nav link) now
            // lives as the "Master Data" left-nav link's own landing content, so it runs first, right
            // after Master Data opens - then the "Event Ack" tab, which (per a later recording) now
            // also hosts the full Event Acknowledgement message CRUD lifecycle directly (previously
            // reached via a separate "Event Check" left-nav path, now folded in here instead of
            // running twice), then the separate, sibling "About" left-nav link - navigation and
            // page-load validation only for that last one. Runs after Configuration, before Infra
            // Management's own NAS/SAN/Cloud/FTP buttons. Config-gated; additive.
            if (Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.master.data", "true"))) {
                MasterDataPage masterData = new MasterDataPage(page);
                if (masterData.open()) {
                    if (config.qcChecklistEnabled()) {
                        QCPage qc = new QCPage(page);
                        // A run-unique suffix on both names, matching the same pattern already used
                        // for this session's own throwaway verification probes - prevents
                        // duplicate-name pile-up across repeated soak runs (confirmed live: earlier
                        // runs against a fixed "Testing" name left several undeleted
                        // "Testing"/"Testing-updated" sections behind whenever a run crashed or
                        // failed before reaching the delete steps, since the QC page has no way to
                        // tell those apart from a fresh run's own section by name alone).
                        String runSuffix = String.valueOf(System.currentTimeMillis() % 100000);
                        String sectionName = ConfigReader.getOrDefault("qc.checklist.section.name", "Testing")
                                + " " + runSuffix;
                        QCSectionData newSection = new QCSectionData(sectionName,
                                ConfigReader.getOrDefault("qc.checklist.section.description", "Testing description"));
                        QCSectionData updatedSection = new QCSectionData(
                                sectionName + ConfigReader.getOrDefault(
                                        "qc.checklist.section.update.name.suffix", "-updated"),
                                ConfigReader.getOrDefault("qc.checklist.section.description", "Testing description")
                                        + ConfigReader.getOrDefault(
                                                "qc.checklist.section.update.description.suffix", "-Updated"));
                        String itemName = ConfigReader.getOrDefault("qc.checklist.item.name", "Testing check list")
                                + " " + runSuffix;
                        QCItemData newItem = new QCItemData(itemName);
                        QCItemData updatedItem = new QCItemData(
                                itemName + ConfigReader.getOrDefault("qc.checklist.item.update.name.suffix", "-Update"));
                        validatePage(page, result, "Master Data - Checklist Lifecycle",
                                () -> qc.runQCChecklistLifecycle(newSection, updatedSection, newItem, updatedItem));
                    }
                    validatePage(page, result, "Master Data - Event Ack", masterData::visitEventAckTab);
                    if (config.eventAcknowledgementEnabled()) {
                        EventAcknowledgementPage eventAck = new EventAcknowledgementPage(page);
                        String eventSuffix = String.valueOf(System.currentTimeMillis());
                        String eventName = ConfigReader.getOrDefault(
                                "event.acknowledgement.message.name", "Testing Event") + " " + eventSuffix;
                        // Separating space added in code, not carried by the config value - see the
                        // identical note on the Group lifecycle's own update-name suffix above for why.
                        String updatedEventName = eventName + " "
                                + ConfigReader.getOrDefault("event.acknowledgement.update.name.suffix", "Update");
                        validatePage(page, result, "Master Data - Event Acknowledgement Lifecycle",
                                () -> eventAck.runEventAcknowledgementLifecycle(eventName, updatedEventName));
                    }
                    validatePage(page, result, "Master Data - About", masterData::visitAboutPage);
                }
            }

            // Settings -> Infra Management: the "NAS/SAN" / "Cloud" / "FTP" buttons on the page
            // itself (distinct from the identically-named Backup-tab toggle switches in Master
            // Configuration / Device Details, neither of which this touches), then Application
            // Settings -> Device ("Enable QC" / "Enable VA Alerts" / Notification Rules toggles).
            // Runs after Master Data - About - confirmed live via a real recording that this whole
            // Infra Management area must run LAST in this Settings walk, not right after "Open
            // settings" (which used to navigate straight here before Organisation ever got a chance
            // to run - a real bug, now fixed by moving it here). Config-gated; additive.
            if (Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.infra.filters", "true"))) {
                InfraPage infraFilters = new InfraPage(page);
                if (infraFilters.open()) {
                    validatePage(page, result, "Infra Management - Filters", infraFilters::exerciseInfraFilters);
                }
            }

            // Application Settings -> Device ("Enable QC" / "Enable VA Alerts" / Notification
            // Rules toggles): still part of the same Infra Management area as the filters above,
            // and still under Admin - runs here, before the VMS Operator role switch below, so no
            // role restore is needed afterward (the whole VMS Operator walk now ends straight in
            // Logout, matching the recorded flow exactly). Config-gated; unchanged otherwise.
            if (config.applicationSettingsEnabled()) {
                InfraPage infra = new InfraPage(page);
                if (infra.open()) {
                    ApplicationSettingsPage applicationSettings = infra.openApplicationSettings();
                    if (applicationSettings.openDevice()) {
                        validatePage(page, result, "Application Settings - Enable QC",
                                applicationSettings::validateEnableQc);
                        validatePage(page, result, "Application Settings - Enable VA Alerts",
                                applicationSettings::validateEnableVaAlerts);
                        // Device -> Notification Rules: random toggle OFF/ON checks.
                        if (Boolean.parseBoolean(
                                ConfigReader.getOrDefault("soak.enable.notification.rules", "true"))) {
                            NotificationRulesPage notificationRules = new NotificationRulesPage(page);
                            validatePage(page, result, "Application Settings - Notification Rules",
                                    notificationRules::runNotificationRulesFlow);
                        }
                    }
                }
            }

            // Infra Management -> profile menu role switch to "VMS Operator", then the full
            // sidebar walk in the recorded page-by-page order (Cameras/Sequence/Audit Logs/Maps/
            // Custom Maps/Archive/Search+Bookmarks/Exports+Snapshots/Audit Logs), ending straight
            // in Logout - matching the recorded flow exactly, with no role restore and no
            // Application Settings in between (that already ran above, under Admin).
            //
            // No separate "VMS Operator - Cameras/Sequence/Audit Logs/Archive/Search/Exports"
            // navigation-only clicks here: confirmed live, every one of the real tests below
            // already does its own navigation to reach its page - Live_view/SequencePage/
            // AuditLogsPage all call their own page.navigate()/navigateTo() to a specific URL
            // (SequencePage's navigateToSequence() even re-confirms VMS Operator mode and clicks
            // "Cameras" -> "Sequence" itself), and EventSearchValidation/ArchiveExportValidation
            // click their own "Search"/"Bookmarks"/"Exports"/"Snapshots" tabs directly. Adding a
            // sidebar click immediately before each of these was a real, confirmed-live bug. not
            // "exercising the sidebar" - clicking a button and then immediately navigating away
            // from it via a fresh page.navigate() just forces an extra full page reload, which is
            // exactly what was bouncing this flow back through Dashboard/Infra Management between
            // pages. Only "Custom Maps" keeps its sidebar click below: it is the one screen with
            // no self-navigating real test of its own.
            VmsOperatorSidebarPage vmsOperatorSidebar = new VmsOperatorSidebarPage(page);
            boolean vmsSidebarRoleSwitchEnabled = Boolean.parseBoolean(
                    ConfigReader.getOrDefault("soak.enable.vms.operator.sidebar", "true"));
            boolean switchedToVmsOperator = !vmsSidebarRoleSwitchEnabled
                    || vmsOperatorSidebar.switchRole("VMS Operator");
            if (vmsSidebarRoleSwitchEnabled) {
                validatePage(page, result, "Infra Management - VMS Operator Role Switch",
                        () -> switchedToVmsOperator);
            // Infra Management -> profile menu role switch to "VMS Operator". A real recording
            // walked the full sidebar under this role (Cameras/Sequence/Audit Logs/Maps/Custom
            // Maps/Archive/Search/Exports/Audit Logs); every one of those screens except "Custom
            // Maps" already has its own real test further down this method, under Admin - moving
            // those tests to run here instead was tried and reverted: live validation showed Live
            // View, Sequence, Audit Logs Search, Map, Event Search, Bookmark Search, Archive
            // Export - Snapshots and Audit Logs - Archive Export all FAIL under VMS Operator
            // (permission/UI differences from Admin) even though every one of them passes cleanly
            // in its original Admin-role position - relocating them would have "fixed" the
            // navigation-only complaint by breaking working tests, which the standing requirement
            // not to affect existing working scripts rules out. So only "VMS Operator - Custom
            // Maps" (the one screen with no functional test anywhere else) runs under this role;
            // the original role is restored immediately after, before Application Settings and
            // everything else resumes under Admin.
            VmsOperatorSidebarPage vmsOperatorSidebar = new VmsOperatorSidebarPage(page);
            boolean vmsSidebarRoleSwitchEnabled = Boolean.parseBoolean(
                    ConfigReader.getOrDefault("soak.enable.vms.operator.sidebar", "true"));
            String roleBeforeVmsOperator = vmsSidebarRoleSwitchEnabled ? vmsOperatorSidebar.currentRoleLabel() : null;
            boolean switchedToVmsOperator = !vmsSidebarRoleSwitchEnabled
                    || vmsOperatorSidebar.switchRole("VMS Operator");
            if (vmsSidebarRoleSwitchEnabled) {
                validatePage(page, result, "Infra Management - VMS Operator Role Switch",
                        () -> switchedToVmsOperator);
            }

            validatePage(page, result, "VMS Operator - Custom Maps", vmsOperatorSidebar::visitCustomMaps);

            if (vmsSidebarRoleSwitchEnabled) {
                boolean restoredRoleAfterVmsOperator = roleBeforeVmsOperator == null
                        || vmsOperatorSidebar.switchRole(roleBeforeVmsOperator);
                validatePage(page, result, "Infra Management - VMS Operator Role Restore",
                        () -> restoredRoleAfterVmsOperator);
            }

            if (config.applicationSettingsEnabled()) {
                InfraPage infra = new InfraPage(page);
                if (infra.open()) {
                    ApplicationSettingsPage applicationSettings = infra.openApplicationSettings();
                    if (applicationSettings.openDevice()) {
                        validatePage(page, result, "Application Settings - Enable QC",
                                applicationSettings::validateEnableQc);
                        validatePage(page, result, "Application Settings - Enable VA Alerts",
                                applicationSettings::validateEnableVaAlerts);
                        // Device -> Notification Rules: random toggle OFF/ON checks.
                        if (Boolean.parseBoolean(
                                ConfigReader.getOrDefault("soak.enable.notification.rules", "true"))) {
                            NotificationRulesPage notificationRules = new NotificationRulesPage(page);
                            validatePage(page, result, "Application Settings - Notification Rules",
                                    notificationRules::runNotificationRulesFlow);
                        }
                    }
                }
            }

            // --- Cameras: Live View (stream available) -> condition-bound 10s continuous stream
            // monitoring -> Snapshot -> Bookmark -> remaining Live View tests (My View CRUD). ---
            if (config.liveEnabled()) {
                // Live View: waits only for the operator panel + camera tiles to become visible,
                // then validates each stream once. No fixed delay before or after.
                long streamStarted = System.nanoTime();
                validatePage(page, result, "Live View", () -> healthPages.validateLiveView(appUrl));
                boolean liveViewPassed = "PASS".equals(result.pageResults.get("Live View"));
                result.liveView = liveViewPassed ? "PASS" : "FAIL";
                result.stream = liveViewPassed ? "PASS" : "FAIL";
                if (liveViewPassed) {
                    result.streamStartupTimeMs = (System.nanoTime() - streamStarted) / 1_000_000;
                } else {
                    result.error = appendError(result.error, "Live View stream validation failed");
                    captureFailureScreenshot(page, evidence, result, config, "live-view-failure.png");
                }

                // Everything below needs a genuinely working Live View (a real camera streaming on
                // the default grid) - Stream Monitoring just watches that same stream, and Snapshot/
                // Bookmark/CRUD all act on "the current camera" on that same grid. When Live View
                // itself already failed (no camera assigned, or its stream never came up), none of
                // these can succeed either; running them anyway only pays each one's own full
                // timeout for no result. So skip straight past this whole group and move on to
                // Sequence, the next real test - never a blind wait on a camera that is not there.
                if (liveViewPassed) {
                    // Continuous stream monitoring for the configured window (soak.liveview.monitor.seconds,
                    // default 10s): re-checks every camera's media state and any new API failure on a
                    // fixed interval until the deadline, then returns - never a blind extra wait once the
                    // window elapses. This is the actual "monitor the stream" requirement; Live View above
                    // already confirmed the stream comes up at all.
                    Page liveViewMonitorPage = page;
                    validatePage(page, result, "Live View - Stream Monitoring",
                            () -> new LiveViewMonitor(liveViewMonitorPage).monitor().isPassed());

                    // Snapshot (post-monitoring): LiveViewCrudPage's own navigateToLiveView() re-lands on
                    // the default Live View grid - a no-op when the page is already there (as it is here,
                    // since CRUD has not run yet), and needed once CRUD (below) starts leaving the page on
                    // its post-delete "My Views" list on later iterations.
                    LiveViewCrudPage liveViewBookmark = new LiveViewCrudPage(page);
                    liveViewBookmark.navigateToLiveView();
                    String snapshotName = "Testing Snapshot " + System.currentTimeMillis();
                    validatePage(page, result, "Live View - Snapshot",
                        () -> liveViewBookmark.createSnapshotOnCurrentCamera(snapshotName));

                    // Bookmark (post-Snapshot): the same camera tile, right after Snapshot
                    // creation/validation above completes - no navigation, no re-selection. Structurally
                    // identical dialog to Snapshot's (confirmed live), so this reuses the SAME
                    // LiveViewCrudPage instance and its selectHumanDetectionType() helper - never a
                    // duplicated implementation.
                    String bookmarkName = "Testing Bookmark " + System.currentTimeMillis();
                    validatePage(page, result, "Live View - Bookmark",
                            () -> liveViewBookmark.createBookmarkOnCurrentCamera(bookmarkName));

                    // Remaining Live View tests: "My Views -> Cameras" CRUD (My View create/update/
                    // delete) - create one view (unique name), change its grid layout, add/remove/re-add
                    // cameras from the device tree, save the configuration, save the view, rename it
                    // (update), then delete it. Runs immediately after Snapshot/Bookmark, per the
                    // requested order. LiveViewCrudPage remains a separate class from
                    // Live_view/LiveViewMonitor, neither of which it calls into.
                    if (config.liveViewCrudEnabled()) {
                        LiveViewCrudPage liveViewCrud = new LiveViewCrudPage(page);
                        String viewSuffix = String.valueOf(System.currentTimeMillis());
                        String viewName = ConfigReader.getOrDefault("live.view.crud.name", "Live View") + " " + viewSuffix;
                        String updatedViewName = viewName
                                + ConfigReader.getOrDefault("live.view.crud.update.name.suffix", "-Update");
                        String gridLayout = ConfigReader.getOrDefault("live.view.crud.grid.layout", "3x3");
                        String[][] cameraPaths = parseCameraPaths(
                                ConfigReader.getOrDefault("live.view.crud.camera.paths", ""));
                        validatePage(page, result, "Live View - CRUD",
                                () -> liveViewCrud.runLiveViewCrudFlow(viewName, updatedViewName, gridLayout, cameraPaths));
                    }
                } else {
                    LOG.warn("[SOAK] Live View failed (no working camera/stream); skipping Stream "
                            + "Monitoring, Snapshot, Bookmark and CRUD, moving on to Sequence.");
                }
            }

            // --- Sequence: "VMS Operator -> Cameras -> Sequence". Create one sequence (unique
            // name, requires at least 2 cameras - confirmed live) with cameras from the device
            // tree, rename it, then delete it. SequencePage.navigateToSequence() reaches this
            // screen entirely on its own (own page.navigate(), own operator-mode check, own
            // "Cameras"/"Sequence" clicks) - no separate sidebar step needed here. ---
            if (config.sequenceEnabled()) {
                SequencePage sequence = new SequencePage(page);
                String sequenceSuffix = String.valueOf(System.currentTimeMillis());
                String sequenceName = ConfigReader.getOrDefault("sequence.crud.name", "Test Sequence")
                        + " " + sequenceSuffix;
                String updatedSequenceName = sequenceName
                        + ConfigReader.getOrDefault("sequence.crud.update.name.suffix", "-Update");
                String[][] sequenceCameraPaths = parseCameraPaths(
                        ConfigReader.getOrDefault("sequence.crud.camera.paths", ""));
                validatePage(page, result, "Sequence",
                        () -> sequence.runSequenceLifecycle(sequenceName, updatedSequenceName, sequenceCameraPaths));
            }

            // --- Audit Logs (post-Sequence): reuses the existing AuditLogsPage untouched export
            // flow, then searches for a run-unique value expected to match nothing (verifying "No
            // records found"), then best-effort searches a known-existing term. Its own
            // navigateToLiveViewAuditLogs() reaches this screen via its own page.navigate() - no
            // separate sidebar step needed here. ---
            if (config.auditLogsSearchEnabled()) {
                AuditLogsPage auditLogsSearch = new AuditLogsPage(page);
                String nonExistentSearchTerm = ConfigReader.getOrDefault("audit.logs.search.nonexistent.term",
                        "AuditSearch") + "_" + System.currentTimeMillis();
                String existingSearchTerm = ConfigReader.getOrDefault("audit.logs.search.existing.term", "");
                validatePage(page, result, "Audit Logs - Search",
                        () -> auditLogsSearch.runAuditLogsSearchFlow(nonExistentSearchTerm, existingSearchTerm));
            }

            // --- Maps + Custom Maps: "Map"/"Map Camera Validation" already self-navigate to
            // /live-views/maps, so they run first, before the "Custom Maps" sidebar nav (which
            // clicks "Maps" itself first, as a submenu-reveal step - not a repeat of this test). ---
            validatePage(page, result, "Map", () -> healthPages.validateMap(appUrl));
            validatePage(page, result, "Map Camera Validation", () -> healthPages.validateMapCameras(appUrl));
            // No separate "Maps" step here: visitCustomMaps() below already clicks "Maps" itself
            // first (required to reveal the "Custom Maps" submenu) - an extra standalone click here
            // would just click the same "Maps" button twice in a row.
            validatePage(page, result, "VMS Operator - Custom Maps", vmsOperatorSidebar::visitCustomMaps);

            // --- Archive: validateArchive() reaches this screen via its own navigateTo() - no
            // separate sidebar step needed here. ---
            validatePage(page, result, "Archive", () -> healthPages.validateArchive(appUrl));
            // Archive Camera Validation already does the whole flow in one pass: open Add Camera,
            // filter to Active, pick one random online device, add it once, then watch its stream for
            // the full monitoring window. This used to be followed by a second addCameraToPlayback()
            // call that added a different, hardcoded camera on top of it, then a third re-navigation
            // to Archive that only checked the "Playback" heading was visible - not the stream itself.
            // One add, one watch, one verdict.
            long playbackStarted = System.nanoTime();
            validatePage(page, result, "Archive Camera Validation", () -> healthPages.validateArchiveCameras(appUrl));
            if (config.playbackEnabled()) {
                result.playback = "PASS".equals(result.pageResults.get("Archive Camera Validation")) ? "PASS" : "FAIL";
                result.playbackStartupTimeMs = (System.nanoTime() - playbackStarted) / 1_000_000;
            }

            // --- Search (+Bookmarks): Event Search (Archive's own "Search"/Events tab) - set a
            // From/To date range wide enough to span two months (confirmed live: a single-month
            // range never enables the calendar's own Apply), verify at least one result comes
            // back, select one dynamically, and confirm its video actually plays. Then Bookmark
            // Search, the same panel's "Bookmarks" tab. EventSearchValidation clicks "Search"/
            // "Bookmarks" itself - no separate sidebar step needed here. ---
            if (config.eventSearchEnabled()) {
                EventSearchValidation eventSearch = new EventSearchValidation(page);
                validatePage(page, result, "Event Search", eventSearch::validateEventSearch);

                // Bookmark Search: the same Search panel's "Bookmarks" tab (a sibling of "Events"),
                // reusing every step of the flow above as-is (date range, Apply, result-count check,
                // dynamic result selection, playback validation) - EventSearchValidation is not
                // duplicated for this, just given a second entry point. Runs only after Event Search
                // above completes.
                validatePage(page, result, "Bookmark Search", eventSearch::validateBookmarkSearch);
            }

            // --- Exports (+Snapshots): Archive Export - Archive -> Exports -> select one export
            // record dynamically -> Preview -> verify the preview video -> Download -> verify the
            // downloaded file. Reuses the existing SoakUiUtils download mechanism (no second
            // download implementation). Then Archive Export - Snapshots, the same Exports tab's
            // "Snapshots" tab, reusing the same ArchiveExportValidation instance and every shared
            // helper it already has. Same config flag as Event/Bookmark Search - no new flag
            // needed, matching that precedent. ArchiveExportValidation clicks "Exports"/
            // "Snapshots" itself - no separate sidebar step needed here. ---
            if (config.eventSearchEnabled() && config.archiveExportEnabled()) {
                ArchiveExportValidation archiveExport = new ArchiveExportValidation(page);
                validatePage(page, result, "Archive Export", archiveExport::validateArchiveExport);
                validatePage(page, result, "Archive Export - Snapshots",
                        archiveExport::validateArchiveSnapshotExport);
            }

            // --- Audit Logs (Archive, second occurrence): the Archive page's own "Audit Logs"
            // navigation button (a sibling of "Exports"), landing on
            // /live-views/archive/audit-logs - a distinct screen from both AuditLogsPage's
            // Settings and Live-View-sidebar Audit Logs flows above, neither of which is
            // modified. Deliberately minimal: navigate -> clear filter -> export -> verify the
            // downloaded file, reusing AuditLogsPage's existing clearDateTimeFilter()/
            // exportAuditLogs()/verifyDownloadedFile() as-is. Same config flags as above - no new
            // flag needed. Its own navigateToArchiveAuditLogs() reaches this screen via its own
            // page.navigate() - no separate sidebar step needed here. ---
            if (config.eventSearchEnabled() && config.archiveExportEnabled()) {
                AuditLogsPage archiveAuditLogs = new AuditLogsPage(page);
                validatePage(page, result, "Audit Logs - Archive Export",
                        archiveAuditLogs::runArchiveAuditLogsExportFlow);
            }

            // Full VMS Operator sidebar walk complete - straight to Logout, matching the recorded
            // flow exactly (no role restore, no Application Settings here: that already ran
            // earlier, under Admin, before the role switch above - see there for why).

            // Archive/Event Search/Bookmark Search/Archive Export are the last validations; logout
            // follows immediately. Settings, Users & Roles and Organisation already ran once above, so they
            // are not repeated here.
            if (config.logoutEnabled()) {
                LOG.info("[SOAK] Logout started");
                if (dashboard.logoutSafely(appUrl)) {
                    result.logout = "PASS";
                    LOG.info("[SOAK] Logout completed | status=PASS");
                } else {
                    // Session still ends when the isolated context is disposed, but the UI control failed.
                    result.logout = "FAIL";
                    result.error = appendError(result.error, "Logout control could not be used");
                    LOG.warn("[SOAK] Logout failed; closing the isolated browser context instead.");
                }
                // The session is over once logout returns, so the browser is shut down here instead
                // of staying open while evidence and reports are written. Tracing has to stop first:
                // stopping it needs a live context.
                if (tracing) {
                    try {
                        Path trace = evidence.resolve("trace/trace.zip");
                        context.tracing().stop(new Tracing.StopOptions().setPath(trace));
                        result.trace = "trace/trace.zip";
                    } catch (Exception exception) {
                        LOG.warn("[SOAK] Trace could not be saved before closing the browser: {}",
                                exception.getMessage());
                    }
                    tracing = false;
                }
                PlaywrightFactory.closeBrowser();
                context = null;
                page = null;
                LOG.info("[SOAK] Browser closed after logout");
            }
            LOG.info("[SOAK] All configured validations completed");
            if (result.pageResults.values().stream().anyMatch(value -> value.startsWith("FAIL"))) {
                throw new SoakValidationFailedException("One or more read-only page validations failed");
            }
            if (!result.apiFailures.isEmpty()) {
                throw new SoakValidationFailedException("HTTP 500/502 responses detected: " + result.apiFailures.size());
            }
            result.overall = "PASS";
        } catch (Exception exception) {
            result.error = exception.getMessage();
            if (exception instanceof SoakValidationFailedException) {
                // Every validation, logout and browser-close above already ran to completion; this is
                // the application being unhealthy, not the automation failing. executionStatus stays
                // "COMPLETED" - only overall (already "FAIL" by default) carries this outcome.
                LOG.warn("[SOAK] {} completed | Validation status=FAIL: {}", result.executionId, result.error);
            } else {
                // Anything else here is exactly what validatePage()/LiveViewMonitor could not isolate:
                // a genuine infrastructure/framework problem (browser crash, Playwright init failure,
                // an unexpected bug), not the application under test - reported distinctly so the two
                // are never confused downstream.
                result.executionStatus = "ERROR";
                result.executionError = exception.getMessage();
                LOG.error("[SOAK] {} EXECUTION ERROR (infrastructure/framework, not an application validation): {}",
                        result.executionId, result.error, exception);
            }
            try {
                if (page != null && config.failureScreenshot()) {
                    Path screenshot = evidence.resolve("screenshots/failure.png");
                    Files.createDirectories(screenshot.getParent());
                    page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setType(ScreenshotType.PNG));
                    result.screenshot = "screenshots/failure.png";
                }
            } catch (Exception ignored) { }
        } finally {
            result.durationMs = (System.nanoTime() - started) / 1_000_000;
            try {
                // Consolidated API failure document, stored beside this execution's other evidence.
                if (ApiMonitor.writeReportToQuietly(evidence) != null) {
                    result.apiFailureLog = "api-failures.log";
                }
                // Failure-centric soak reports under target/soak-test/run-<timestamp>/. The execution
                // ID and full per-page results ride along so the consolidated report below can
                // correlate and aggregate runs without re-deriving anything already computed here.
                SoakReporter.writeAll(result.overall, iteration, result.executionId, result.pageResults);
                if (tracing && context != null) {
                    Path trace = evidence.resolve("trace/trace.zip");
                    context.tracing().stop(new Tracing.StopOptions().setPath(trace));
                    result.trace = "trace/trace.zip";
                }
                JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("result.json").toFile(), result);
                LOG.info("[SOAK REPORT] Final report generated");
            } catch (Exception ignored) { }
            try {
                // Additive reporting layer only: rescans every completed run under target/soak-test
                // and rewrites the multi-run report. Never affects pass/fail or any flow above.
                SoakConsolidatedReportGenerator.generate();
                LOG.info("[SOAK REPORT] Consolidated report updated");
            } catch (Exception ignored) { }
            try {
                // One JMX per soak run, containing every API this run's own ApiMonitor capture
                // actually observed (never skipped, never the separate load-test HAR/registry
                // inventory) - additive only, caught here exactly like every other reporting step
                // above so a JMX-generation problem can never affect this run's own result.
                SoakApiJmxGenerator.generate(SoakRunContext.current().runDirectory(),
                        "VigilX SoakTest All APIs " + result.executionId);
            } catch (Exception ignored) { }
            PlaywrightFactory.closeBrowser();
        }
        LOG.info("[SOAK] Execution completed");
        LOG.info("[SOAK] Validation status: {}", result.overall);
        LOG.info("[SOAK] Execution status: {}", result.executionStatus);
        return result;
    }

    public static void runPeriodically() {
        SoakTestConfig config = SoakTestConfig.load();
        long end = System.nanoTime() + config.durationHours() * 3_600_000_000_000L;
        do {
            runOnce();
            long remaining = end - System.nanoTime();
            if (remaining <= 0) break;
            try { Thread.sleep(Math.min(config.intervalMinutes() * 60_000L, remaining / 1_000_000)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        } while (true);
    }

    /**
     * Runs one page's validation, then blocks until that page's API traffic has drained before
     * returning - so the next page never starts while calls are still in flight.
     *
     * <p>The UI check and the API check are combined: a page passes only when both pass. Set
     * {@code api.page.validation.strict=false} to keep API failures reported but non-fatal.
     */
    private static void validatePage(Page page, SoakResult result, String name, PageCheck check) {
        LOG.info("[SOAK] {} validation started", name);
        // Defensive: a stray earlier capture should never be mistaken for this check's own evidence.
        ScreenshotUtils.clearLastCapturedScreenshot();

        // Session-resume, proactive case: a long soak run can hit a session timeout (or an earlier
        // step's action can unexpectedly end the session - confirmed live, this has happened) between
        // one check and the next. A no-op when the session is already active.
        ensureLoggedIn(page);

        PageApiTracker tracker = PageApiTracker.start(page, name);
        boolean uiPassed = false;
        String uiError = null;
        Throwable uiException = null;
        try {
            uiPassed = check.validate();
        } catch (RuntimeException exception) {
            uiError = exception.getMessage();
            uiException = exception;
            LOG.error("[SOAK] {} validation failed: {}", name, exception.getMessage(), exception);
        }

        // Session-resume, reactive case: the session was ended DURING this check, not before it -
        // detected by the check having failed and the login screen now showing. Log back in and
        // retry this exact check once - "resume from where it stopped" at the granularity this
        // page-object-per-check architecture supports - rather than restarting the whole run or
        // letting every remaining check fail against a dead session.
        if (!uiPassed && isOnLoginPage(page)) {
            LOG.warn("[SOAK] {} - session was logged out mid-check; logging back in and retrying", name);
            if (ensureLoggedIn(page)) {
                tracker = PageApiTracker.start(page, name);
                uiError = null;
                uiException = null;
                try {
                    uiPassed = check.validate();
                } catch (RuntimeException exception) {
                    uiError = exception.getMessage();
                    uiException = exception;
                    LOG.error("[SOAK] {} validation failed after re-login retry: {}", name,
                            exception.getMessage(), exception);
                }
            }
        }

        // Always drain the page's APIs, even when the UI check already failed. The UI outcome is
        // passed in so a healthy page is not screenshotted just because a background API failed.
        // uiError/uiException let the tracker's own safety-net capture log a real reason when the
        // check has no failure-capture of its own, instead of only a generic message.
        ApiMonitor.PageApiResult apiResult = tracker.finish(uiPassed && uiError == null, uiError, uiException);
        boolean apiPassed = apiResult.isPassed() || !strictApiValidation();

        String status;
        if (uiError != null) {
            status = "FAIL: " + uiError;
        } else if (uiPassed && apiPassed) {
            status = "PASS";
        } else if (!uiPassed) {
            status = "FAIL";
        } else {
            status = "FAIL (API): " + apiResult.failed() + " failed, " + apiResult.timeouts() + " timed out";
        }

        result.pageResults.put(name, status);

        // Failure-centric reporting: only failures produce a record; passes are counted only.
        if (status.startsWith("FAIL")) {
            String failureType = uiError != null ? "EXCEPTION"
                    : (!uiPassed ? "UI_FAILURE" : "API_FAILURE");
            String reason = uiError != null ? uiError
                    : (!uiPassed ? name + " UI validation returned false"
                            : name + " had " + apiResult.failed() + " failed API(s) and "
                                    + apiResult.timeouts() + " timeout(s)");
            // Evidence for this failure: the check's own capture when it took one, otherwise the
            // tracker's safety-net screenshot - either way, a Soak Test screenshot/log now backs
            // every recorded failure instead of the report's screenshot field staying empty.
            SoakReporter.recordPageFailure(name, failureType, apiResult.failed(),
                    tracker.lastFailureScreenshot(), reason);
        } else {
            SoakReporter.recordPagePassed();
        }

        LOG.info("[SOAK] {} validation completed | status={} | apiRequests={} apiFailed={} apiTimeouts={}",
                name, status, apiResult.requests(), apiResult.failed(), apiResult.timeouts());
    }

    /** True when the login form (username field + "Login" button) is currently on screen. */
    private static boolean isOnLoginPage(Page page) {
        try {
            LoginPage loginPage = new LoginPage(page);
            return loginPage.isUsernameDisplayed() && loginPage.isLoginButtonDisplayed();
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Logs back in if (and only if) the login screen is currently showing - a no-op returning
     * {@code true} immediately when the session is already active. Called both proactively (right
     * before every check) and reactively (right after a check fails, in case the session died mid-
     * check), so a soak run that hits a session timeout or an unexpected logout resumes from
     * whichever check was running instead of every remaining check failing against a dead session.
     */
    private static boolean ensureLoggedIn(Page page) {
        if (!isOnLoginPage(page)) {
            return true;
        }
        try {
            LOG.warn("[SOAK] Session appears logged out; logging back in.");
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            LOG.info("[SOAK] Re-login completed; resuming.");
            return true;
        } catch (Exception exception) {
            LOG.error("[SOAK] Re-login failed: {}", exception.getMessage());
            return false;
        }
    }

    /** When true (default) a page's API failures fail the page; otherwise they are report-only. */
    private static boolean strictApiValidation() {
        try {
            return Boolean.parseBoolean(ConfigReader.getOrDefault("api.page.validation.strict", "true"));
        } catch (Exception exception) {
            return true;
        }
    }

    private static String appendError(String existing, String next) {
        if (existing == null || existing.isBlank()) return next;
        if (next == null || next.isBlank()) return existing;
        return existing + "; " + next;
    }

    /**
     * Parses {@code live.view.crud.camera.paths} - semicolon-separated camera tree paths, each a
     * "&gt;"-separated sequence of tree-item names ending in the camera itself (e.g.
     * {@code "Site A>Camera 1;Site B>Camera 2"}) - into the path arrays
     * {@link LiveViewCrudPage#addCamera(String...)} expects. Blank config yields an empty array
     * (the caller then adds no cameras, rather than guessing at real device names).
     */
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

    private static void captureFailureScreenshot(Page page, Path evidence, SoakResult result, SoakTestConfig config, String fileName) {
        try {
            if (page != null && config.failureScreenshot()) {
                Path screenshot = evidence.resolve("screenshots/" + fileName);
                Files.createDirectories(screenshot.getParent());
                page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setType(ScreenshotType.PNG));
                result.screenshot = "screenshots/" + fileName;
            }
        } catch (Exception ignored) { }
    }

    @FunctionalInterface
    private interface PageCheck { boolean validate(); }
}
