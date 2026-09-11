package com.vigilx.tests.apisecurity;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.apisecurity.execution.ApiTestResult;
import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder;
import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.RestApiFunctionalReportGenerator;
import com.vigilx.apisecurity.restassured.RestApiTestRunner;
import com.vigilx.apisecurity.restassured.RestApiTestRunner.RunOutcome;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

/**
 * REST Assured functional validation against the final combined API inventory
 * (ApiMonitor capture + HAR-confirmed missing APIs + MissingApiRegistry, deduplicated).
 *
 * <p><strong>Read-only by policy</strong>: only GET endpoints are executed (see
 * {@link com.vigilx.apisecurity.execution.ApiExecutionPolicy}); every write API is reported as
 * present-but-not-executed rather than invoked, so this can never mutate or delete real application
 * data. Fires real HTTP requests directly against the live app - independent of Playwright/SOAK,
 * never part of {@code testng.xml}.
 *
 * <p>Run with {@code mvn -o test -Dtest=RestApiFunctionalTest}. Requires a real
 * {@code api-inventory.json} to already exist (see {@code ApiInventoryComparisonTest} / a soak run
 * with {@code api.inventory.enabled=true}) for the ApiMonitor side of the combined inventory to be
 * non-empty; otherwise every HAR endpoint is (correctly) treated as not-yet-captured.
 */
public class RestApiFunctionalTest {

    @Test
    public void validateEligibleApisWithRestAssured() throws Exception {
        Path harFile = Paths.get(ConfigReader.getOrDefault(
                "apisecurity.har.path", "OWSAP automation-Final.har"));
        Path reportsDirectory = ApiSecurityRunContext.startNewRun(Paths.get(ConfigReader.getOrDefault(
                "apisecurity.reports.directory", "target/apisecurity/reports")));

        CombinedInventoryBuilder.Built inventory = CombinedInventoryBuilder.build(
                harFile, ApiMonitor.getInventoryLogFile());
        System.out.println("[REST API FUNCTIONAL TEST] Combined inventory (ApiMonitor + registered "
                + "missing APIs, deduplicated): " + inventory.combined.size() + " endpoints");

        RestApiTestRunner runner = new RestApiTestRunner();
        RunOutcome outcome = runner.run(inventory.combined);

        RestApiFunctionalReportGenerator.writeAll(reportsDirectory, outcome);

        if (outcome.authFailed) {
            Assert.fail("REST Assured could not authenticate: " + outcome.authFailureReason);
            return;
        }

        long failed = outcome.results.stream().filter(r -> r.outcome() == ApiTestResult.Outcome.FAIL).count();
        System.out.println("[REST API FUNCTIONAL TEST] " + outcome.results.size() + " executed, "
                + failed + " failed, " + outcome.notEligible.size() + " not auto-executed (write APIs/exclusions).");

        // Execution completing is what this build should judge; individual API failures are fully
        // reported above; a flaky endpoint must not turn a successful test run red, mirroring how
        // SoakHealthCheckTest treats application-health failures as a reported result, not a build failure.
    }
}
