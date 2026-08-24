package com.vigilx.apisecurity.performance;

import com.vigilx.config.ConfigReader;

/**
 * A JMeter thread-group profile: how many virtual users, how long to ramp them up, how long the
 * whole run lasts. Read from environment variables first (matching the brief's own
 * {@code JMETER_USERS}/{@code JMETER_RAMP_UP}/{@code JMETER_DURATION} naming), then config
 * properties, then a deliberately conservative smoke-test default - never an arbitrary hard-coded
 * "load" or "stress" number.
 */
public final class LoadProfile {

    private final int users;
    private final int rampUpSeconds;
    private final int durationSeconds;
    private final int thinkTimeMs;

    private LoadProfile(int users, int rampUpSeconds, int durationSeconds, int thinkTimeMs) {
        this.users = users;
        this.rampUpSeconds = rampUpSeconds;
        this.durationSeconds = durationSeconds;
        this.thinkTimeMs = thinkTimeMs;
    }

    public static LoadProfile fromConfig() {
        int users = resolve("JMETER_USERS", "apisecurity.jmeter.users", 5);
        int rampUp = resolve("JMETER_RAMP_UP", "apisecurity.jmeter.rampup.seconds", 10);
        int duration = resolve("JMETER_DURATION", "apisecurity.jmeter.duration.seconds", 30);
        int thinkTime = resolve(null, "apisecurity.jmeter.think.time.ms", 500);
        return new LoadProfile(users, rampUp, duration, thinkTime);
    }

    public int users() { return users; }
    public int rampUpSeconds() { return rampUpSeconds; }
    public int durationSeconds() { return durationSeconds; }
    public int thinkTimeMs() { return thinkTimeMs; }

    private static int resolve(String envVar, String configKey, int fallback) {
        if (envVar != null) {
            String env = System.getenv(envVar);
            if (env != null && !env.isBlank()) {
                try {
                    return Integer.parseInt(env.trim());
                } catch (NumberFormatException ignored) {
                    // Fall through to config/default.
                }
            }
        }
        try {
            String value = ConfigReader.getOrDefault(configKey, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }
}
