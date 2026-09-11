package com.vigilx.soak;

import com.vigilx.config.ConfigReader;

/** Configuration for the opt-in periodic UI health check. */
public record SoakTestConfig(
        boolean enabled, int durationHours, int intervalMinutes, String cameraName,
        String cameraId, boolean liveEnabled, boolean playbackEnabled, boolean alertEnabled,
        boolean alertRequired, boolean logoutEnabled, boolean passScreenshot,
        boolean failureScreenshot, boolean failureTrace, String outputDirectory, int streamTimeoutMs) {

    public static SoakTestConfig load() {
        return new SoakTestConfig(
                ConfigReader.getBoolean("soak.enabled"), ConfigReader.getInt("soak.duration.hours"),
                ConfigReader.getInt("soak.interval.minutes"), ConfigReader.get("soak.camera.name"),
                ConfigReader.getOrDefault("soak.camera.id", ""), ConfigReader.getBoolean("soak.enable.live"),
                ConfigReader.getBoolean("soak.enable.playback"), ConfigReader.getBoolean("soak.enable.alert"),
                ConfigReader.getBoolean("soak.alert.required"),
                ConfigReader.getBoolean("soak.enable.logout"), ConfigReader.getBoolean("soak.capture.pass.screenshot"),
                ConfigReader.getBoolean("soak.capture.failure.screenshot"), ConfigReader.getBoolean("soak.capture.failure.trace"),
                ConfigReader.get("soak.output.directory"), ConfigReader.getInt("soak.stream.timeout.ms"));
    }
}
