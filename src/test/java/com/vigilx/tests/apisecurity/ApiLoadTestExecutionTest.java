package com.vigilx.tests.apisecurity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.apisecurity.execution.ApiAuthClient;
import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder;
import com.vigilx.apisecurity.performance.CrudLifecycleGrouper;
import com.vigilx.apisecurity.performance.JmeterRunner;
import com.vigilx.apisecurity.performance.LoadProfile;
import com.vigilx.apisecurity.performance.LoadTestJmxValidator;
import com.vigilx.apisecurity.performance.LoadTestPlanBuilder;
import com.vigilx.apisecurity.performance.LoadTestResultAnalyzer;
import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.LoadTestReportGenerator;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

/**
 * Load test built entirely from the already-captured/monitored API inventory (the same
 * {@link CombinedInventoryBuilder} + {@link ApiMonitor} + HAR combination every other test in this
 * module already uses) - an additional step run AFTER that existing capture/monitoring flow, never a
 * replacement for it. Nothing here touches {@code ApiMonitor}, the HAR parser, the inventory builder,
 * or any of their existing output; this class only reads what they already produced.
 *
 * <p><strong>Not GET-only</strong>, unlike every other auto-executed load/functional test in this
 * module - by explicit, informed request: every captured API regardless of method (GET/POST/PUT/
 * PATCH/DELETE) is included, using {@link ApiExecutionPolicy#isEligibleForNegativeTest} (any method,
 * still honoring the same data-driven exclusion list every other check already respects) rather than
 * {@link ApiExecutionPolicy#isEligibleForFunctionalTest} (GET-only). This deliberately departs from
 * this module's own established safety default: concurrent virtual users really will create/mutate/
 * delete real data on every write endpoint captured, repeatedly, for the whole run - accepted here as
 * the explicit point of this specific load test, not a general-purpose stance. Add any endpoint that
 * must never be hit this way to {@code excluded-apis.json} (the same file already used elsewhere).
 *
 * <p>Output tree, one timestamped run per execution (via the same {@link ApiSecurityRunContext}
 * convention the rest of this module uses), each subfolder created only when it actually has
 * something to hold:
 * <pre>
 * target/load-test/run-&lt;timestamp&gt;/
 *   jmx/VigilX_API_Load_Test.jmx           - the exact plan JMeter executed
 *   results/load-test-results.csv/.json    - every tested API's real PASS/FAIL numbers
 *   reports/VigilX_API_Load_Test_Report.html
 *   logs/load-test.log                     - JMeter's own CLI log for this run
 * </pre>
 *
 * <p>Never part of {@code testng.xml}; run with {@code mvn -o test -Dtest=ApiLoadTestExecutionTest}.
 */
public class ApiLoadTestExecutionTest {

    private static final String TEST_PLAN_NAME = "VigilX API Load Test";

    @Test
    public void runApiLoadTest() throws Exception {
        long runStarted = System.currentTimeMillis();

        Path harFile = Paths.get(ConfigReader.getOrDefault(
                "apisecurity.har.path", "OWSAP automation-Final.har"));
        Path loadTestRoot = ApiSecurityRunContext.startNewRun(
                Paths.get(ConfigReader.getOrDefault("apisecurity.loadtest.directory", "target/load-test")));

        System.out.println("=== Using already-captured/monitored API inventory ===");
        CombinedInventoryBuilder.Built inventory = CombinedInventoryBuilder.build(
                harFile, ApiMonitor.getInventoryLogFile());

        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        List<ApiDefinition> testedApis = inventory.combined.stream()
                .filter(policy::isEligibleForNegativeTest)
                .toList();

        if (testedApis.isEmpty()) {
            Assert.fail("No eligible captured APIs available to load-test.");
            return;
        }
        long writeApiCount = testedApis.stream().filter(d -> !"GET".equals(d.method())).count();
        System.out.println("[LOAD TEST] " + testedApis.size() + " already-captured API(s) selected "
                + "(all methods, not GET-only - " + writeApiCount + " write endpoint(s) included by "
                + "explicit request; excluded-apis.json entries are still honored).");

        String scheme = ConfigReader.getOrDefault("apisecurity.restassured.scheme", "http");
        String authHost = resolveAuthHost(inventory.combined, testedApis);
        String token;
        try {
            token = ApiAuthClient.login(scheme, authHost);
        } catch (Exception exception) {
            Assert.fail("Load test could not authenticate: " + exception.getMessage());
            return;
        }

        String host = testedApis.get(0).host();
        String domain = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        int port = host.contains(":") ? Integer.parseInt(host.substring(host.indexOf(':') + 1)) : 80;

        LoadProfile profile = LoadProfile.fromConfig();
        System.out.println("[LOAD TEST] " + testedApis.size() + " endpoints | " + profile.users()
                + " users, " + profile.rampUpSeconds() + "s ramp-up, " + profile.durationSeconds() + "s duration.");

        // Grouped once here (a pure function of testedApis, same deterministic result as the identical
        // call LoadTestPlanBuilder.build() makes internally) so the analyzer can align chained
        // UPDATE/DELETE samplers back to the exact same resource/variable names the JMX actually used.
        CrudLifecycleGrouper.GroupingResult crudGrouping = CrudLifecycleGrouper.group(testedApis);
        System.out.println("[LOAD TEST] " + crudGrouping.groups().size() + " CRUD lifecycle(s) discovered "
                + "(create->update/delete chained with a real captured id), " + crudGrouping.standalone().size()
                + " standalone endpoint(s).");

        String jmx = LoadTestPlanBuilder.build(TEST_PLAN_NAME, domain, port, token, testedApis, profile);

        // JMeterRunner writes its own working copy (jmx/jtl/html-report/log) into whatever
        // apisecurity.performance.directory currently resolves to - scoped to this run's own folder
        // via a JVM system property (the same pattern SoakRunContext.start() already uses to redirect
        // legacy validators' screenshot dirs) rather than touching JmeterRunner itself, which
        // JmeterPerformanceTest still relies on to write to its own default location.
        String previousPerformanceDir = System.getProperty("apisecurity.performance.directory");
        System.setProperty("apisecurity.performance.directory", loadTestRoot.resolve("results/raw").toString());
        JmeterRunner.RunResult runResult;
        try {
            runResult = new JmeterRunner().run("VigilX_API_Load_Test", jmx, profile);
        } finally {
            if (previousPerformanceDir == null) {
                System.clearProperty("apisecurity.performance.directory");
            } else {
                System.setProperty("apisecurity.performance.directory", previousPerformanceDir);
            }
        }

        if (!runResult.executed) {
            Assert.fail("Load test JMeter run did not complete: " + runResult.error);
            return;
        }

        // The exact plan JMeter actually executed, copied to the requested name/location - never a
        // second, possibly-different build of the plan.
        Path jmxDestination = loadTestRoot.resolve("jmx").resolve("VigilX_API_Load_Test.jmx");
        Files.createDirectories(jmxDestination.getParent());
        Files.copy(runResult.jmxFile, jmxDestination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        LoadTestJmxValidator.ValidationResult jmxValidation = LoadTestJmxValidator.validate(jmxDestination);
        System.out.println("[LOAD TEST] JMX validation: " + (jmxValidation.isValid() ? "VALID" : "INVALID")
                + " (" + jmxValidation.httpSamplerCount + " HTTP samplers, "
                + jmxValidation.responseAssertionCount + " response assertions)");
        if (!jmxValidation.isValid()) {
            for (String problem : jmxValidation.problems) {
                System.err.println("[LOAD TEST] JMX problem: " + problem);
            }
        }

        LoadTestResultAnalyzer.AnalysisResult analysis = LoadTestResultAnalyzer.analyze(
                runResult.jtlFile, testedApis, crudGrouping.groups());
        analysis.totalCaptured = inventory.combined.size();

        Path resultsDirectory = loadTestRoot.resolve("results");
        LoadTestReportGenerator.writeResults(resultsDirectory, analysis);

        long durationMs = System.currentTimeMillis() - runStarted;
        Path reportsDirectory = loadTestRoot.resolve("reports");
        LoadTestReportGenerator.writeHtmlReport(reportsDirectory, TEST_PLAN_NAME, profile, analysis,
                jmxValidation, jmxDestination, durationMs);

        if (Files.isRegularFile(runResult.jtlFile.getParent().resolve("VigilX_API_Load_Test-jmeter.log"))) {
            Path logDestination = loadTestRoot.resolve("logs").resolve("load-test.log");
            Files.createDirectories(logDestination.getParent());
            Files.copy(runResult.jtlFile.getParent().resolve("VigilX_API_Load_Test-jmeter.log"), logDestination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        System.out.println("=== LOAD TEST COMPLETE ===");
        System.out.println("Total APIs captured : " + analysis.totalCaptured);
        System.out.println("Included in load test: " + analysis.totalIncluded());
        System.out.println("Total APIs executed  : " + analysis.totalExecuted());
        System.out.println("Total APIs skipped   : " + analysis.totalSkipped());
        System.out.println("Total requests    : " + analysis.totalRequests);
        System.out.println("Passed            : " + analysis.totalPassed
                + " (" + String.format("%.1f", analysis.passPercent()) + "%)");
        System.out.println("Failed            : " + analysis.totalFailed
                + " (" + String.format("%.1f", analysis.failPercent()) + "%)");
        System.out.println("Run folder        : " + loadTestRoot.toAbsolutePath());
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
}
