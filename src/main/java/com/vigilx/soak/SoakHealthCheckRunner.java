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
import com.vigilx.pages.ApplicationHealthPage;
import com.vigilx.pages.ApplicationSettingsPage;
import com.vigilx.pages.ArchiveExportValidation;
import com.vigilx.pages.AuditLogsPage;
import com.vigilx.pages.DeviceDetailsValidation;
import com.vigilx.pages.EventAcknowledgementPage;
import com.vigilx.pages.EventSearchValidation;
import com.vigilx.pages.InfraPage;
import com.vigilx.pages.GroupData;
import com.vigilx.pages.GroupsPage;
import com.vigilx.pages.LicensePage;
import com.vigilx.pages.LiveViewCrudPage;
import com.vigilx.pages.MasterConfigurationValidation;
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
                boolean alertPassed = healthPages.validateAlerts(appUrl, alertScreenshot);
                // soak.alert.required=false was never honoured: a video alert that carries only
                // images still failed the whole run. The status must not start with FAIL, since
                // that is what marks the run failed further down.
                String alertStatus = alertPassed ? "PASS"
                        : config.alertRequired() ? "FAIL"
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

            // Application Settings -> Infra Management -> Application Settings -> Device:
            // dynamically toggles "Enable QC" and "Enable VA Alerts". Runs after License, before
            // Users & Roles / Organisation. Config-gated; additive.
            if (config.applicationSettingsEnabled()) {
                InfraPage infra = new InfraPage(page);
                if (infra.open()) {
                    ApplicationSettingsPage applicationSettings = infra.openApplicationSettings();
                    if (applicationSettings.openDevice()) {
                        validatePage(page, result, "Application Settings - Enable QC",
                                applicationSettings::validateEnableQc);
                        validatePage(page, result, "Application Settings - Enable VA Alerts",
                                applicationSettings::validateEnableVaAlerts);
                    }
                }
            }

            // Settings -> Users & Roles -> Users -> Add User -> full user lifecycle: creates one
            // user, verifies it, then runs its lifecycle (inactivate/activate/edit/delete). Runs
            // after Application Settings, before the existing Users & Roles / Organisation checks
            // (untouched). Config-gated; additive.
            if (config.userCreationEnabled()) {
                UsersRolesPage usersRoles = new UsersRolesPage(page);
                UserData newUser = new UserData(
                        ConfigReader.getOrDefault("user.creation.first.name", "Pradeep"),
                        ConfigReader.getOrDefault("user.creation.last.name", "Testing"),
                        ConfigReader.getOrDefault("user.creation.email", "pradeep.m+50@solutionchamps.com"),
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
                    RoleData newRole = new RoleData(
                            ConfigReader.getOrDefault("role.creation.name", "Testing role"), true);
                    RoleData updatedRole = new RoleData(
                            ConfigReader.getOrDefault("role.creation.name", "Testing role")
                                    + ConfigReader.getOrDefault("role.update.name.suffix", "-update"),
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
                    GroupData newGroup = new GroupData(
                            ConfigReader.getOrDefault("group.creation.name", "Testing group"),
                            true, true, true);
                    GroupData updatedGroup = new GroupData(
                            // The separating space is added here in code, not carried by the config
                            // value - confirmed live: ConfigReader.getOrDefault() trims its result,
                            // which silently discards a leading space even when the properties file
                            // escapes it, so a config-carried " update" always collapsed back to
                            // "update" and produced "Testing groupupdate" with no space.
                            ConfigReader.getOrDefault("group.creation.name", "Testing group") + " "
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
            validatePage(page, result, "Users & Roles", healthPages::validateUsersAndRoles);
            validatePage(page, result, "Organisation", healthPages::validateOrganisation);
            // Settings -> Organisation -> Profile: uploads a new organization logo (guaranteeing no
            // leftover crop window - including the native OS file-picker a headed browser would
            // otherwise pop open), then updates Admin Details / Primary Contact (once saved
            // unchanged, once with a new Last Name) / Address Details, verifying each save's toast
            // and the value actually shown afterward. Runs right after the existing Organisation
            // check above (which lands on this same page and is left untouched) and reuses the
            // existing OrganizationPage class rather than creating a new one. Additive and
            // config-gated.
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
                    // Audit Logs tab (next to License / Project Information) with no new code.
                    AuditLogsPage organisationAuditLogs = new AuditLogsPage(page);
                    validatePage(page, result, "Organisation - Audit Logs Export",
                            organisationAuditLogs::runAuditLogsExportFlow);
                }
            }

            // Settings -> QC: checklist section + checklist item lifecycle - create a section,
            // add one item to it, verify both, rename the section and the item, delete the item,
            // then delete the section (only after its item is gone). Runs after the Organisation ->
            // Project Information -> License -> Audit Logs chain above. Additive and config-gated;
            // independent of every other page class above, none of which it modifies.
            if (config.qcChecklistEnabled()) {
                QCPage qc = new QCPage(page);
                // A run-unique suffix on both names, matching the same pattern already used for
                // this session's own throwaway verification probes - prevents duplicate-name
                // pile-up across repeated soak runs (confirmed live: earlier runs against a fixed
                // "Testing" name left several undeleted "Testing"/"Testing-updated" sections behind
                // whenever a run crashed or failed before reaching the delete steps, since the QC
                // page has no way to tell those apart from a fresh run's own section by name alone).
                String runSuffix = String.valueOf(System.currentTimeMillis() % 100000);
                String sectionName = ConfigReader.getOrDefault("qc.checklist.section.name", "Testing") + " " + runSuffix;
                QCSectionData newSection = new QCSectionData(sectionName,
                        ConfigReader.getOrDefault("qc.checklist.section.description", "Testing description"));
                QCSectionData updatedSection = new QCSectionData(
                        sectionName + ConfigReader.getOrDefault("qc.checklist.section.update.name.suffix", "-updated"),
                        ConfigReader.getOrDefault("qc.checklist.section.description", "Testing description")
                                + ConfigReader.getOrDefault("qc.checklist.section.update.description.suffix", "-Updated"));
                String itemName = ConfigReader.getOrDefault("qc.checklist.item.name", "Testing check list") + " " + runSuffix;
                QCItemData newItem = new QCItemData(itemName);
                QCItemData updatedItem = new QCItemData(
                        itemName + ConfigReader.getOrDefault("qc.checklist.item.update.name.suffix", "-Update"));
                validatePage(page, result, "QC - Checklist Lifecycle",
                        () -> qc.runQCChecklistLifecycle(newSection, updatedSection, newItem, updatedItem));
            }

            // Settings -> Event Check -> Event Acknowledgement: create one message, verify it is
            // listed, rename it, verify the rename, delete it, and verify it is gone - validating
            // each operation's background API and success toast. Runs after the QC checklist
            // lifecycle above. Additive and config-gated; independent of QCPage and every other
            // page class above, none of which it modifies.
            if (config.eventAcknowledgementEnabled()) {
                EventAcknowledgementPage eventAck = new EventAcknowledgementPage(page);
                String eventSuffix = String.valueOf(System.currentTimeMillis());
                String eventName = ConfigReader.getOrDefault("event.acknowledgement.message.name", "Testing Event")
                        + " " + eventSuffix;
                // Separating space added in code, not carried by the config value - see the
                // identical note on the Group lifecycle's own update-name suffix above for why.
                String updatedEventName = eventName + " "
                        + ConfigReader.getOrDefault("event.acknowledgement.update.name.suffix", "Update");
                validatePage(page, result, "Event Acknowledgement - Lifecycle",
                        () -> eventAck.runEventAcknowledgementLifecycle(eventName, updatedEventName));
            }

            // Execution order under liveEnabled(): Live View (stream available) -> condition-bound
            // 10s continuous stream monitoring -> Bookmark -> Snapshot -> remaining Live View tests
            // (My View CRUD). Live_view confirms the stream comes up (single pass, no blind waits);
            // LiveViewMonitor then owns the actual timed monitoring window - it already existed,
            // fully built and config-driven (soak.liveview.monitor.seconds), but was never called
            // from anywhere, so no real monitoring window was ever run. My View CRUD used to run
            // BEFORE Bookmark/Snapshot; it now runs after them, per the requested order - nothing
            // about what any of these steps DO has changed, only where in the sequence CRUD runs.
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
            }

            // Sequence: "VMS Operator -> Cameras -> Sequence". Create one sequence (unique name,
            // requires at least 2 cameras - confirmed live) with cameras from the device tree,
            // rename it, then delete it. Runs only after the existing Live View monitoring and its
            // continuous camera/API validation above complete - never before, and never calling
            // into Live_view or LiveViewCrudPage, both of which are left completely untouched.
            // Additive and config-gated.
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

            // Audit Logs (post-Sequence): reuses the existing AuditLogsPage untouched export flow,
            // then searches for a run-unique value expected to match nothing (verifying "No records
            // found"), then best-effort searches a known-existing term. Runs only after the Sequence
            // lifecycle above completes - never before it. Additive and config-gated; independent of
            // the earlier Audit Logs Export checks elsewhere in this runner (a separate flag), which
            // are untouched.
            if (config.auditLogsSearchEnabled()) {
                AuditLogsPage auditLogsSearch = new AuditLogsPage(page);
                String nonExistentSearchTerm = ConfigReader.getOrDefault("audit.logs.search.nonexistent.term",
                        "AuditSearch") + "_" + System.currentTimeMillis();
                String existingSearchTerm = ConfigReader.getOrDefault("audit.logs.search.existing.term", "");
                validatePage(page, result, "Audit Logs - Search",
                        () -> auditLogsSearch.runAuditLogsSearchFlow(nonExistentSearchTerm, existingSearchTerm));
            }

            validatePage(page, result, "Map", () -> healthPages.validateMap(appUrl));
            validatePage(page, result, "Map Camera Validation", () -> healthPages.validateMapCameras(appUrl));
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

            // Event Search (Archive's own "Search"/Events tab): set a From/To date range wide
            // enough to span two months (confirmed live: a single-month range never enables the
            // calendar's own Apply), verify at least one result comes back, select one dynamically,
            // and confirm its video actually plays. Runs only after Archive Camera Validation above
            // - never before it, and never modifying ArchiveValidation itself (a separate class).
            // Additive and config-gated.
            if (config.eventSearchEnabled()) {
                EventSearchValidation eventSearch = new EventSearchValidation(page);
                validatePage(page, result, "Event Search", eventSearch::validateEventSearch);

                // Bookmark Search: the same Search panel's "Bookmarks" tab (a sibling of "Events"),
                // reusing every step of the flow above as-is (date range, Apply, result-count check,
                // dynamic result selection, playback validation) - EventSearchValidation is not
                // duplicated for this, just given a second entry point. Runs only after Event Search
                // above completes.
                validatePage(page, result, "Bookmark Search", eventSearch::validateBookmarkSearch);

                // Archive Export: Archive -> Exports -> select one export record dynamically ->
                // Preview -> verify the preview video -> Download -> verify the downloaded file.
                // Reuses the existing SoakUiUtils download mechanism (no second download
                // implementation). Runs only after Event Search and Bookmark Search above complete -
                // never modifying either of those, or ArchiveValidation. Additive and config-gated.
                if (config.archiveExportEnabled()) {
                    ArchiveExportValidation archiveExport = new ArchiveExportValidation(page);
                    validatePage(page, result, "Archive Export", archiveExport::validateArchiveExport);

                    // Archive Export - Snapshots: the same Exports tab's own "Snapshots" tab,
                    // reusing the same ArchiveExportValidation instance and every shared helper it
                    // already has (row discovery, dialog closing, download/file verification, Logs
                    // search/clear, delete confirmation) - never a duplicated implementation, and
                    // never modifying the Video Export flow above. Runs only after Archive Export
                    // (Video) completes. Same config flag - no new flag needed, matching the
                    // Event/Bookmark Search precedent.
                        validatePage(page, result, "Archive Export - Snapshots",
                            archiveExport::validateArchiveSnapshotExport);

                    // Audit Logs (post-Snapshot): the Archive page's own "Audit Logs" navigation
                    // button (a sibling of "Exports"), landing on /live-views/archive/audit-logs -
                    // a distinct screen from both AuditLogsPage's Settings and Live-View-sidebar
                    // Audit Logs flows above, neither of which is modified. Deliberately minimal:
                    // navigate -> clear filter -> export -> verify the downloaded file, reusing
                    // AuditLogsPage's existing clearDateTimeFilter()/exportAuditLogs()/
                    // verifyDownloadedFile() as-is. Runs only after Archive Export - Snapshots
                    // above completes. Same config flag - no new flag needed.
                        AuditLogsPage archiveAuditLogs = new AuditLogsPage(page);
                        validatePage(page, result, "Audit Logs - Archive Export",
                            archiveAuditLogs::runArchiveAuditLogsExportFlow);
                }
            }

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
