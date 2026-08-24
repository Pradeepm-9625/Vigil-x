package com.vigilx.tests.apisecurity;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder;
import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.OwaspSecurityReportGenerator;
import com.vigilx.apisecurity.security.OwaspSecurityTestRunner;
import com.vigilx.apisecurity.security.OwaspSecurityTestRunner.RunResult;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

/**
 * OWASP API Security Top 10 checks (Steps 8+9 of the brief, unified: the curl-style checks are
 * implemented in Java + REST Assured rather than shelled-out curl, for the same reasons the rest of
 * this module avoids ad-hoc scripting - reusable, typed, testable).
 *
 * <p>Read-only by policy (GET-only, see {@code ApiExecutionPolicy}); a handful of small, controlled,
 * non-destructive probes (invalid tokens, one wrong-password login attempt, a small rate-limit burst,
 * loopback/metadata-address SSRF probes, benign XSS/SQLi-shaped query values). Never part of
 * {@code testng.xml}; run with {@code mvn -o test -Dtest=OwaspSecurityTest}.
 */
public class OwaspSecurityTest {

    @Test
    public void runOwaspApiSecurityChecks() throws Exception {
        Path harFile = Paths.get(ConfigReader.getOrDefault(
                "apisecurity.har.path", "OWSAP automation-Final.har"));
        Path reportsDirectory = ApiSecurityRunContext.startNewRun(Paths.get(ConfigReader.getOrDefault(
                "apisecurity.reports.directory", "target/apisecurity/reports")));

        CombinedInventoryBuilder.Built inventory = CombinedInventoryBuilder.build(
                harFile, ApiMonitor.getInventoryLogFile());

        OwaspSecurityTestRunner runner = new OwaspSecurityTestRunner();
        RunResult result = runner.run(inventory.combined, inventory.comparison);

        OwaspSecurityReportGenerator.writeAll(reportsDirectory, result);

        if (result.authFailed) {
            Assert.fail("OWASP security checks could not authenticate: " + result.authFailureReason);
            return;
        }

        long failed = result.findings.stream().filter(f -> f.verdict() == Verdict.FAIL).count();
        long notAssessed = result.findings.stream().filter(f -> f.verdict() == Verdict.NOT_ASSESSED).count();
        System.out.println("[OWASP SECURITY TEST] " + result.findings.size() + " findings: "
                + failed + " FAIL, " + notAssessed + " NOT_ASSESSED, "
                + (result.findings.size() - failed - notAssessed) + " PASS.");

        // Execution completing is what this build should judge; every finding (including FAILs) is
        // fully reported above, mirroring how SoakHealthCheckTest treats application findings as a
        // reported result, not a build failure.
    }
}
