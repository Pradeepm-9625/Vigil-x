package com.vigilx.tests.apisecurity;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.annotations.Test;

import com.vigilx.apisecurity.reporting.ApiSecurityRunContext;
import com.vigilx.apisecurity.reporting.ConsolidatedReportGenerator;
import com.vigilx.config.ConfigReader;

/**
 * Builds the final consolidated API security/performance report from whatever sub-reports already
 * exist on disk (api-inventory-comparison, api-functional, owasp-security, zap, jmeter). Pure
 * aggregation - reads already-written JSON, makes no HTTP calls, touches nothing under
 * {@code target/soak-test/**}. Safe to run any time, in any order, as many times as needed.
 *
 * <p>Never part of {@code testng.xml}; run with {@code mvn -o test -Dtest=FinalConsolidatedReportTest}
 * after running whichever of the other apisecurity test classes you want reflected in it.
 */
public class FinalConsolidatedReportTest {

    @Test
    public void generateFinalReport() {
        Path reportsDirectory = ApiSecurityRunContext.latestRun(Paths.get(ConfigReader.getOrDefault(
                "apisecurity.reports.directory", "target/apisecurity/reports")));
        ConsolidatedReportGenerator.generate(reportsDirectory);
    }
}
