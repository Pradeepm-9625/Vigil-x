package com.vigilx.tests.apisecurity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.apisecurity.execution.ApiAuthClient;
import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder;
import com.vigilx.apisecurity.inventory.ApiCategory;
import com.vigilx.apisecurity.inventory.ApiClassifier;
import com.vigilx.apisecurity.performance.JmeterPlanBuilder;
import com.vigilx.apisecurity.performance.JmeterRunner;
import com.vigilx.apisecurity.performance.JtlResultParser;
import com.vigilx.apisecurity.performance.LoadProfile;
import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.JmeterReportGenerator;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

/**
 * JMeter performance testing (Step 11) against the READ (GET) category of the final combined API
 * inventory - the only category this framework auto-executes, matching the GET-only policy used by
 * every other consumer of this inventory.
 *
 * <p>Every other category (CREATE/UPDATE/DELETE/AUTH/STREAMING/CONTROL/DEVICE/REPORTING) is
 * classified and written to {@code api-classification-report.csv} for visibility, but never
 * load-tested automatically: concurrent virtual users hitting a write endpoint would multiply real
 * mutations against the live application, and load-testing AUTH would mean repeatedly hitting
 * {@code /auth/login}, which is itself rate-limited (observed 5/window) - a single token is obtained
 * once and shared by every virtual user instead.
 *
 * <p>Deliberately conservative smoke-level defaults (5 users / 10s ramp-up / 30s duration) - never
 * "load" or "stress" numbers - overridable via {@code JMETER_USERS}/{@code JMETER_RAMP_UP}/
 * {@code JMETER_DURATION} env vars per the brief, or the equivalent {@code apisecurity.jmeter.*}
 * properties. Reuses the JMeter installation already on this machine.
 *
 * <p>Never part of {@code testng.xml}; run with {@code mvn -o test -Dtest=JmeterPerformanceTest}.
 */
public class JmeterPerformanceTest {

    @Test
    public void runReadCategoryLoadTest() throws Exception {
        Path harFile = Paths.get(ConfigReader.getOrDefault(
                "apisecurity.har.path", "OWSAP automation-Final.har"));
        Path reportsDirectory = ApiSecurityRunContext.startNewRun(Paths.get(ConfigReader.getOrDefault(
                "apisecurity.reports.directory", "target/apisecurity/reports")));

        CombinedInventoryBuilder.Built inventory = CombinedInventoryBuilder.build(
                harFile, ApiMonitor.getInventoryLogFile());

        writeClassificationReport(reportsDirectory, inventory.combined);

        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        List<ApiDefinition> readApis = inventory.combined.stream()
                .filter(policy::isEligibleForFunctionalTest)
                .filter(definition -> ApiClassifier.classify(definition) == ApiCategory.READ
                        || definition.method().equals("GET"))
                .toList();

        if (readApis.isEmpty()) {
            Assert.fail("No eligible READ (GET) APIs available to load-test.");
            return;
        }

        String scheme = ConfigReader.getOrDefault("apisecurity.restassured.scheme", "http");
        String authHost = resolveAuthHost(inventory.combined, readApis);
        String token;
        try {
            token = ApiAuthClient.login(scheme, authHost);
        } catch (Exception exception) {
            Assert.fail("JMeter run could not authenticate: " + exception.getMessage());
            return;
        }

        // All eligible READ APIs share one host in practice; JMeter's HTTP Request Defaults needs one.
        String host = readApis.get(0).host();
        String domain = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        int port = host.contains(":") ? Integer.parseInt(host.substring(host.indexOf(':') + 1)) : 80;

        LoadProfile profile = LoadProfile.fromConfig();
        System.out.println("[JMETER] Scenario: READ | " + readApis.size() + " endpoints | "
                + profile.users() + " users, " + profile.rampUpSeconds() + "s ramp-up, "
                + profile.durationSeconds() + "s duration.");

        String jmx = JmeterPlanBuilder.build("VigilX READ API Load Test", domain, port, token, readApis, profile);

        JmeterRunner runner = new JmeterRunner();
        JmeterRunner.RunResult runResult = runner.run("read", jmx, profile);

        if (!runResult.executed) {
            Assert.fail("JMeter run did not complete: " + runResult.error);
            return;
        }

        JtlResultParser.Summary summary = JtlResultParser.parse(runResult.jtlFile);
        JmeterReportGenerator.writeAll(reportsDirectory, "read", profile, summary, runResult.htmlReportDir);

        System.out.println("[JMETER] " + summary.totalSamples + " samples, " + summary.errorCount
                + " errors (" + String.format("%.2f", summary.errorRatePercent) + "%), avg="
                + String.format("%.0f", summary.avgMs) + "ms, p95=" + String.format("%.0f", summary.p95Ms) + "ms.");
    }

    private void writeClassificationReport(Path reportsDirectory, List<ApiDefinition> combined) throws Exception {
        Files.createDirectories(reportsDirectory);
        Map<ApiCategory, Integer> counts = new EnumMap<>(ApiCategory.class);
        StringBuilder csv = new StringBuilder("Method,Endpoint,Host,Category,AutoLoadTested\n");
        for (ApiDefinition definition : combined) {
            ApiCategory category = ApiClassifier.classify(definition);
            counts.merge(category, 1, Integer::sum);
            boolean autoTested = category == ApiCategory.READ;
            csv.append(definition.method()).append(',')
                    .append(csvField(definition.normalizedPath())).append(',')
                    .append(definition.host()).append(',')
                    .append(category).append(',')
                    .append(autoTested ? "YES" : "NO")
                    .append('\n');
        }
        Files.writeString(reportsDirectory.resolve("api-classification-report.csv"), csv.toString(), StandardCharsets.UTF_8);

        System.out.println("[JMETER] API classification: " + counts);
        System.out.println("[JMETER REPORT] Classification report written to "
                + reportsDirectory.resolve("api-classification-report.csv"));
    }

    private static String resolveAuthHost(List<ApiDefinition> combinedInventory, List<ApiDefinition> eligible) {
        String configured = ConfigReader.getOrDefault("apisecurity.auth.host", "");
        if (!configured.isBlank()) {
            return configured;
        }
        for (ApiDefinition definition : combinedInventory) {
            if ("/auth/login".equals(definition.normalizedPath())) {
                return definition.host();
            }
        }
        return eligible.get(0).host();
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
