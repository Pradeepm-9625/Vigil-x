package com.vigilx.tests.apisecurity;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder;
import com.vigilx.apisecurity.inventory.MissingApiRegistry;
import com.vigilx.apisecurity.reporting.ApiComparisonReportGenerator;
import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.FinalInventoryReportGenerator;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;

/**
 * Standalone API-inventory pipeline: ApiMonitor's captured inventory vs. a HAR trace, normalized,
 * compared, deduplicated, with {@link MissingApiRegistry} automatically revalidated/regenerated
 * (brief Phase 6) and the FINAL UNIQUE API INVENTORY (brief Phase 7) exported.
 *
 * <p><strong>Deliberately not part of {@code testng.xml}</strong> and never touches a browser or the
 * live application - it only reads files (the HAR and ApiMonitor's inventory export) and rewrites
 * the registry file. This keeps the new API security/performance automation module completely
 * separate from the existing SOAK suite: nothing here can affect SOAK execution, and SOAK never
 * runs this.
 *
 * <p>Run directly with {@code mvn -o test -Dtest=ApiInventoryComparisonTest}.
 *
 * <p>The ApiMonitor side of the comparison is only as real as the inventory file on disk: if nobody
 * has yet run a capture pass with {@code api.inventory.enabled=true}, ApiMonitor's side reports zero
 * APIs (never fabricated), so every HAR endpoint correctly shows up as "missing" until that real
 * capture exists.
 */
public class ApiInventoryComparisonTest {

    @Test
    public void compareApiMonitorInventoryAgainstHar() throws Exception {
        Path harFile = Paths.get(ConfigReader.getOrDefault(
                "apisecurity.har.path", "OWSAP automation-Final.har"));
        Path reportsDirectory = ApiSecurityRunContext.startNewRun(Paths.get(ConfigReader.getOrDefault(
                "apisecurity.reports.directory", "target/apisecurity/reports")));

        System.out.println("[API INVENTORY COMPARISON] HAR file: " + harFile.toAbsolutePath());
        Path monitorInventoryFile = ApiMonitor.getInventoryLogFile();
        System.out.println("[API INVENTORY COMPARISON] ApiMonitor inventory file: " + monitorInventoryFile);

        CombinedInventoryBuilder.Built built = CombinedInventoryBuilder.build(harFile, monitorInventoryFile);
        Assert.assertFalse(built.comparison.harApis().isEmpty(), "HAR parsing produced zero entries from " + harFile);

        System.out.println(built.comparison.renderSummary());
        System.out.println("[MISSING API REGISTRY] " + MissingApiRegistry.sourceFile().toAbsolutePath()
                + " -> " + built.registryRegeneration.added.size() + " added, "
                + built.registryRegeneration.removed.size() + " removed, "
                + built.registryRegeneration.unchanged + " unchanged.");
        System.out.println("[FINAL UNIQUE API INVENTORY] " + built.finalRecords.size() + " record(s).");

        ApiComparisonReportGenerator.writeAll(reportsDirectory, built.comparison, MissingApiRegistry.getMissingApis());
        FinalInventoryReportGenerator.writeAll(reportsDirectory, built.finalRecords);

        System.out.println("[API INVENTORY COMPARISON] Reports written to " + reportsDirectory.toAbsolutePath());
    }
}
