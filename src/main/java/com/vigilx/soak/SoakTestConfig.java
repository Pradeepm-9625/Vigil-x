package com.vigilx.soak;

import com.vigilx.config.ConfigReader;

/** Configuration for the opt-in periodic UI health check. */
public record SoakTestConfig(
        boolean enabled, int durationHours, int intervalMinutes, String cameraName,
        String cameraId, boolean liveEnabled, boolean playbackEnabled, boolean alertEnabled,
        boolean alertRequired, boolean logoutEnabled, boolean passScreenshot,
        boolean failureScreenshot, boolean failureTrace, String outputDirectory, int streamTimeoutMs,
        boolean dashboardTabsEnabled, boolean deviceDetailsEnabled, boolean projectHierarchyEnabled,
        boolean reportsEnabled, boolean masterConfigurationEnabled, boolean applicationSettingsEnabled,
        boolean userCreationEnabled, boolean roleCreationEnabled, boolean groupCreationEnabled,
        boolean organisationLogoEnabled, boolean auditLogsExportEnabled, boolean projectInformationEnabled,
        boolean licenseValidationEnabled, boolean qcChecklistEnabled, boolean eventAcknowledgementEnabled,
        boolean liveViewCrudEnabled, boolean sequenceEnabled, boolean auditLogsSearchEnabled,
        boolean eventSearchEnabled, boolean archiveExportEnabled) {

    public static SoakTestConfig load() {
        return new SoakTestConfig(
                ConfigReader.getBoolean("soak.enabled"), ConfigReader.getInt("soak.duration.hours"),
                ConfigReader.getInt("soak.interval.minutes"), ConfigReader.get("soak.camera.name"),
                ConfigReader.getOrDefault("soak.camera.id", ""), ConfigReader.getBoolean("soak.enable.live"),
                ConfigReader.getBoolean("soak.enable.playback"), ConfigReader.getBoolean("soak.enable.alert"),
                ConfigReader.getBoolean("soak.alert.required"),
                ConfigReader.getBoolean("soak.enable.logout"), ConfigReader.getBoolean("soak.capture.pass.screenshot"),
                ConfigReader.getBoolean("soak.capture.failure.screenshot"), ConfigReader.getBoolean("soak.capture.failure.trace"),
                ConfigReader.get("soak.output.directory"), ConfigReader.getInt("soak.stream.timeout.ms"),
                // All default to true and read through getOrDefault, so an older config.properties
                // without these keys keeps working untouched.
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.dashboard.tabs", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.device.details", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.project.hierarchy", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.reports", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.master.configuration", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.application.settings", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.user.creation", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.role.creation", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.group.creation", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.organisation.logo", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.audit.logs.export", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.project.information", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.license.validation", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.qc.checklist", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.event.acknowledgement", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.live.view.crud", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.sequence", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.audit.logs.search", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.event.search", "true")),
                Boolean.parseBoolean(ConfigReader.getOrDefault("soak.enable.archive.export", "true")));
    }
}
