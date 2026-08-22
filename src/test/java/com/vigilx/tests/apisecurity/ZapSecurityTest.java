package com.vigilx.tests.apisecurity;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder;
import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.ZapReportGenerator;
import com.vigilx.apisecurity.zap.ZapScanRunner;
import com.vigilx.apisecurity.zap.ZapScanRunner.RunResult;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

/**
 * OWASP ZAP scan (Step 10) against the final combined API inventory.
 *
 * <p>Passive-scan only by default ({@code zap.active.scan.enabled=false}): every eligible GET URL is
 * fed through ZAP via {@code accessUrl}, which triggers ZAP's passive analysis with no attack traffic
 * sent. Active scanning (real attack payloads) is opt-in and, when enabled, still targets only
 * individual URLs already approved by {@code ApiExecutionPolicy} - never a full-site spider+scan,
 * never a write endpoint.
 *
 * <p>Reuses (does not replace) the local ZAP install already present on this machine; starts a
 * headless daemon only if one is not already running, and stops only the instance it started.
 *
 * <p>Never part of {@code testng.xml}; run with {@code mvn -o test -Dtest=ZapSecurityTest}.
 */
public class ZapSecurityTest {

    @Test
    public void runZapScan() throws Exception {
        Path harFile = Paths.get(ConfigReader.getOrDefault(
                "apisecurity.har.path", "OWSAP automation-Final.har"));
        Path reportsDirectory = ApiSecurityRunContext.startNewRun(Paths.get(ConfigReader.getOrDefault(
                "apisecurity.reports.directory", "target/apisecurity/reports")));

        CombinedInventoryBuilder.Built inventory = CombinedInventoryBuilder.build(
                harFile, ApiMonitor.getInventoryLogFile());

        ZapScanRunner runner = new ZapScanRunner();
        RunResult result = runner.run(inventory.combined);

        ZapReportGenerator.writeAll(reportsDirectory, result);

        if (!result.daemonAvailable) {
            Assert.fail("ZAP daemon was not available and could not be auto-started - see "
                    + reportsDirectory.resolve("zap-daemon.log") + " for details.");
            return;
        }
        if (result.authFailed) {
            Assert.fail("ZAP scan could not authenticate: " + result.authFailureReason);
            return;
        }

        System.out.println("[ZAP SECURITY TEST] " + result.urlsFedToPassiveScan + " URLs passively scanned, "
                + (result.activeScanEnabled ? result.urlsActivelyScanned + " actively scanned, " : "active scan disabled, ")
                + result.alerts.size() + " alert(s) raised.");

        // Execution completing is what this build should judge; every alert is fully reported above.
    }
}
