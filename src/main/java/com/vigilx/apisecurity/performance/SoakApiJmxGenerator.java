package com.vigilx.apisecurity.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.inventory.ApiMonitorInventoryReader;

/**
 * Generates one JMeter {@code .jmx} plan per soak run, containing every distinct API that run's own
 * {@code ApiMonitor} capture actually observed - never a filtered subset, and never the separate
 * HAR/registry-merged inventory the API-security load test uses. Purely additive: called once, at
 * the very end of a soak execution, after every existing soak step (validations, reports, the
 * consolidated report) has already run; a failure here is caught and logged, never allowed to affect
 * the soak run's own pass/fail outcome or any of its existing output.
 *
 * <p>Reuses {@link LoadTestPlanBuilder} as-is - the exact same JMX structure (CRUD lifecycle
 * chaining, Response Assertions, real captured request bodies) the API-security load test already
 * produces and this session already live-verified - rather than a second, divergent JMX writer.
 */
public final class SoakApiJmxGenerator {

    private SoakApiJmxGenerator() {
    }

    /**
     * Builds the plan from every API this JVM's {@code ApiMonitor} has captured so far
     * ({@link ApiMonitorInventoryReader#readInProcess()} - the real, in-memory record of this exact
     * run, not a re-read of a file that could race with it being written) and writes it to
     * {@code <soakRunDirectory>/jmx/<planName>.jmx}. Returns the written path, or {@code null} if
     * nothing could be written (directory creation or write failure) - callers treat that as
     * non-fatal, matching every other additive reporting step in the soak runner.
     */
    public static Path generate(Path soakRunDirectory, String planName) {
        try {
            List<ApiDefinition> capturedApis = ApiMonitorInventoryReader.readInProcess();
            System.out.println("[SOAK JMX] " + capturedApis.size() + " distinct API(s) captured this run "
                    + "- generating JMX (none skipped).");

            // No REST bearer token is available here - a soak run authenticates through the real UI
            // login, not ApiAuthClient's API login, so the generated plan's Authorization header is
            // left blank rather than inventing a value; every other captured header/body/status is
            // real. host/port default to the app's own base.url so the plan is immediately editable
            // in JMeter even when every captured sample happens to share one host (the common case).
            String host = capturedApis.isEmpty() ? "" : capturedApis.get(0).host();
            String domain = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
            int port = host.contains(":") ? parsePortSafe(host.substring(host.indexOf(':') + 1)) : 80;

            String jmx = LoadTestPlanBuilder.build(planName, domain, port, "", capturedApis,
                    LoadProfile.fromConfig());

            Path jmxDirectory = soakRunDirectory.resolve("jmx");
            Files.createDirectories(jmxDirectory);
            Path destination = jmxDirectory.resolve(planName + ".jmx");
            Files.writeString(destination, jmx, StandardCharsets.UTF_8);
            System.out.println("[SOAK JMX] Written: " + destination.toAbsolutePath());
            return destination;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[SOAK JMX] Could not generate this run's JMX file: " + exception.getMessage());
            return null;
        }
    }

    private static int parsePortSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 80;
        }
    }
}
