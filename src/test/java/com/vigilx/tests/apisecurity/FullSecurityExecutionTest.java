package com.vigilx.tests.apisecurity;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.vigilx.apisecurity.execution.ApiAuthClient;
import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.execution.HealthChecker;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder;
import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder.Built;
import com.vigilx.apisecurity.performance.JmeterPlanBuilder;
import com.vigilx.apisecurity.performance.JmeterRunner;
import com.vigilx.apisecurity.performance.JtlResultParser;
import com.vigilx.apisecurity.performance.LoadProfile;
import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.SecurityTestReportOrganizer;
import com.vigilx.apisecurity.restassured.RestApiTestRunner;
import com.vigilx.apisecurity.security.OwaspSecurityTestRunner;
import com.vigilx.apisecurity.zap.ZapScanRunner;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

/**
 * Runs the COMPLETE existing API/security/performance automation end to end, in one execution, and
 * reorganizes every real result into {@code target/security-test-report/}.
 *
 * <p>Reuses every existing runner/generator exactly as built for the individual test classes
 * ({@code RestApiFunctionalTest}, {@code OwaspSecurityTest}, {@code ZapSecurityTest},
 * {@code JmeterPerformanceTest}) - nothing here is a second implementation of any of them, only a
 * single orchestration that runs them in sequence and hands their real result objects straight to
 * {@link SecurityTestReportOrganizer}, with a health check (brief Phase 33) before each high-impact
 * phase: if the app stops responding, remaining phases are marked BLOCKED, not silently skipped or
 * fabricated.
 *
 * <p>Never part of {@code testng.xml}; run with {@code mvn -o test -Dtest=FullSecurityExecutionTest}.
 * Reuses whatever {@code api-inventory.json} already exists - run a soak pass with
 * {@code api.inventory.enabled=true} first for a non-empty ApiMonitor side.
 */
public class FullSecurityExecutionTest {

    @Test
    public void runCompleteApiSecurityPerformanceFlow() throws Exception {
        Path harFile = Paths.get(ConfigReader.getOrDefault("apisecurity.har.path", "OWSAP automation-Final.har"));
        Path reportRoot = ApiSecurityRunContext.startNewRun(Paths.get("target/security-test-report"));
        List<String> blockedPhases = new ArrayList<>();

        System.out.println("=== STEP 1-13: Build final unique API inventory (ApiMonitor + HAR, "
                + "normalized, compared, deduplicated, registry revalidated) ===");
        Built inventory = CombinedInventoryBuilder.build(harFile, ApiMonitor.getInventoryLogFile());
        System.out.println(inventory.comparison.renderSummary());
        System.out.println("Final unique API inventory: " + inventory.finalRecords.size() + " records.");

        String scheme = ConfigReader.getOrDefault("apisecurity.restassured.scheme", "http");
        String host = firstHost(inventory);

        System.out.println("=== HEALTH CHECK before positive/negative testing ===");
        if (host == null || !HealthChecker.isHealthy(scheme, host)) {
            String reason = "SERVER_UNAVAILABLE before positive testing - no eligible host or health check failed";
            System.err.println("[HEALTH CHECK] " + reason);
            blockedPhases.add(reason);
            writeBlockedReport(reportRoot, inventory, blockedPhases);
            return;
        }

        System.out.println("=== STEP 14: Positive API tests (REST Assured) ===");
        RestApiTestRunner.RunOutcome positive = new RestApiTestRunner().run(inventory.combined);
        System.out.println(positive.results.size() + " executed, "
                + positive.results.stream().filter(r -> r.isPassed()).count() + " passed.");

        System.out.println("=== HEALTH CHECK before security/OWASP testing ===");
        if (!HealthChecker.isHealthy(scheme, host)) {
            blockedPhases.add("SERVER_UNAVAILABLE before OWASP/negative testing");
        }

        System.out.println("=== STEP 15-17: Negative + OWASP API Security Top 10 tests ===");
        OwaspSecurityTestRunner.RunResult owasp = blockedPhases.isEmpty()
                ? new OwaspSecurityTestRunner().run(inventory.combined, inventory.comparison)
                : blockedOwaspResult();
        System.out.println(owasp.findings.size() + " findings recorded.");

        System.out.println("=== HEALTH CHECK before ZAP ===");
        boolean healthyForZap = HealthChecker.isHealthy(scheme, host);
        if (!healthyForZap) {
            blockedPhases.add("SERVER_UNAVAILABLE before ZAP scan");
        }

        System.out.println("=== STEP 18: OWASP ZAP ===");
        ZapScanRunner.RunResult zap = healthyForZap ? new ZapScanRunner().run(inventory.combined)
                : blockedZapResult();
        System.out.println("ZAP: daemonAvailable=" + zap.daemonAvailable + " alerts=" + zap.alerts.size());

        System.out.println("=== HEALTH CHECK before JMeter ===");
        boolean healthyForJmeter = HealthChecker.isHealthy(scheme, host);
        if (!healthyForJmeter) {
            blockedPhases.add("SERVER_UNAVAILABLE before JMeter");
        }

        System.out.println("=== STEP 19-20: Generate + run safe JMeter scenario ===");
        SecurityTestReportOrganizer.JmeterRunResult jmeterResult;
        if (!healthyForJmeter) {
            jmeterResult = new SecurityTestReportOrganizer.JmeterRunResult(false, "Blocked: server unhealthy before JMeter",
                    null, null, null, null, new JtlResultParser.Summary(), LoadProfile.fromConfig());
        } else {
            try {
                jmeterResult = runJmeter(inventory, scheme);
            } catch (Exception exception) {
                // A liveness health check alone does not guarantee login itself is not currently
                // rate-limited by earlier phases in this same run - without this, an auth failure here
                // would propagate past STEP 21-24 and leave this entire execution with no report at
                // all, silently losing the real inventory/REST Assured/OWASP/ZAP results already
                // gathered above. Degrade to a documented blocked phase instead, exactly like every
                // other phase in this class already does.
                String reason = "JMeter phase could not run: " + exception.getMessage();
                System.err.println("[FULL SECURITY EXECUTION] " + reason);
                blockedPhases.add(reason);
                jmeterResult = new SecurityTestReportOrganizer.JmeterRunResult(false, reason, null, null, null, null,
                        new JtlResultParser.Summary(), LoadProfile.fromConfig());
            }
        }

        System.out.println("=== STEP 21-24: Generate reports under " + reportRoot.toAbsolutePath() + " ===");
        SecurityTestReportOrganizer.writeAll(reportRoot, inventory, positive, owasp, zap, jmeterResult, blockedPhases);

        System.out.println("=== COMPLETE. Blocked phases: " + blockedPhases.size() + " ===");
    }

    private SecurityTestReportOrganizer.JmeterRunResult runJmeter(Built inventory, String scheme) throws Exception {
        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        List<ApiDefinition> readApis = inventory.combined.stream()
                .filter(policy::isEligibleForFunctionalTest)
                .toList();
        if (readApis.isEmpty()) {
            return new SecurityTestReportOrganizer.JmeterRunResult(false, "No eligible GET APIs to load-test",
                    null, null, null, null, new JtlResultParser.Summary(), LoadProfile.fromConfig());
        }

        String authHost = resolveAuthHost(inventory, readApis);
        String token = ApiAuthClient.login(scheme, authHost);

        String host = readApis.get(0).host();
        String domain = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        int port = host.contains(":") ? Integer.parseInt(host.substring(host.indexOf(':') + 1)) : 80;

        LoadProfile profile = LoadProfile.fromConfig();
        String jmx = JmeterPlanBuilder.build("VigilX Full Execution - READ Load Test", domain, port, token, readApis, profile);

        JmeterRunner runner = new JmeterRunner();
        JmeterRunner.RunResult runResult = runner.run("full-execution-read", jmx, profile);
        if (!runResult.executed) {
            return new SecurityTestReportOrganizer.JmeterRunResult(false, runResult.error, runResult.jmxFile,
                    runResult.jtlFile, runResult.htmlReportDir, null, new JtlResultParser.Summary(), profile);
        }

        JtlResultParser.Summary summary = JtlResultParser.parse(runResult.jtlFile);
        Path logFile = runResult.jmxFile.getParent().resolve("full-execution-read-jmeter.log");
        return new SecurityTestReportOrganizer.JmeterRunResult(true, null, runResult.jmxFile, runResult.jtlFile,
                runResult.htmlReportDir, logFile, summary, profile);
    }

    private void writeBlockedReport(Path reportRoot, Built inventory, List<String> blockedPhases) throws Exception {
        RestApiTestRunner.RunOutcome blockedPositive = new RestApiTestRunner.RunOutcome();
        blockedPositive.authFailed = true;
        blockedPositive.authFailureReason = "SERVER_UNAVAILABLE - health check failed before any testing began";
        SecurityTestReportOrganizer.writeAll(reportRoot, inventory, blockedPositive, blockedOwaspResult(),
                blockedZapResult(),
                new SecurityTestReportOrganizer.JmeterRunResult(false, "Blocked: server unavailable", null, null,
                        null, null, new JtlResultParser.Summary(), LoadProfile.fromConfig()),
                blockedPhases);
    }

    private OwaspSecurityTestRunner.RunResult blockedOwaspResult() {
        OwaspSecurityTestRunner.RunResult result = new OwaspSecurityTestRunner.RunResult();
        result.authFailed = true;
        result.authFailureReason = "SERVER_UNAVAILABLE - blocked by health check";
        return result;
    }

    private ZapScanRunner.RunResult blockedZapResult() {
        ZapScanRunner.RunResult result = new ZapScanRunner.RunResult();
        result.daemonAvailable = false;
        return result;
    }

    private static String firstHost(Built inventory) {
        return inventory.finalRecords.isEmpty() ? null : inventory.finalRecords.get(0).definition().host();
    }

    private static String resolveAuthHost(Built inventory, List<ApiDefinition> eligible) {
        String configured = ConfigReader.getOrDefault("apisecurity.auth.host", "");
        if (!configured.isBlank()) {
            return configured;
        }
        for (ApiDefinition definition : inventory.combined) {
            if ("/auth/login".equals(definition.normalizedPath())) {
                return definition.host();
            }
        }
        return eligible.get(0).host();
    }
}
