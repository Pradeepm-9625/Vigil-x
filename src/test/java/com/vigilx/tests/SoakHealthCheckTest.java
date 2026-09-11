package com.vigilx.tests;

import java.nio.file.Path;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import com.vigilx.reporting.SoakRunContext;
import com.vigilx.soak.SoakHealthCheckRunner;
import com.vigilx.soak.SoakResult;
import com.vigilx.soak.SoakTestConfig;
import com.vigilx.utils.LoggerUtils;

/**
 * Opt-in entry point: mvn test -Dtest=SoakHealthCheckTest -Dsoak.enabled=true
 *
 * <p>Two separate outcomes come out of one soak run, and only one of them belongs to Maven/TestNG:
 * <ul>
 *   <li><b>Execution status</b> - did the automation itself run to completion? Every configured
 *       validation attempted, logout run, browser closed, all reports written. This is what a build
 *       failure should mean. {@link SoakHealthCheckRunner#runOnce()} only reports "ERROR" here for a
 *       genuine infrastructure/framework problem it could not isolate - a browser crash, a Playwright
 *       init failure, an unexpected bug - never for the application under test being unhealthy.
 *   <li><b>Validation status</b> ({@link SoakResult#overall}) - is the <em>application</em> healthy?
 *       API failures, a camera that will not stream, a page that would not load: all still recorded
 *       as FAIL, with full evidence, in {@code result.json} and both the individual and consolidated
 *       reports. This test deliberately does not assert on it: a flaky backend must not turn a soak
 *       run that did its job correctly into a red Maven build.
 * </ul>
 */
public class SoakHealthCheckTest {
    private static final Logger LOG = LoggerUtils.getLogger(SoakHealthCheckTest.class);

    @Test
    public void runConfiguredHealthCheck() {
        if (!SoakTestConfig.load().enabled()) throw new SkipException("Set soak.enabled=true to run soak checks");

        SoakResult result = SoakHealthCheckRunner.runOnce();

        if ("ERROR".equals(result.executionStatus)) {
            // The soak itself could not run to completion - this, and only this, is a test failure.
            Assert.fail(executionErrorMessage(result));
            return;
        }

        // Execution completed normally. Application health is a separate, already fully-reported
        // concern - surface it clearly in the console without failing the build over it.
        if ("PASS".equals(result.overall)) {
            LOG.info("[SOAK] TEST EXECUTION COMPLETED SUCCESSFULLY. Application validation: PASS.");
        } else {
            LOG.warn(applicationValidationSummary(result));
        }
    }

    private static String executionErrorMessage(SoakResult result) {
        return "Soak execution ERROR [" + result.executionId + "]: " + value(result.executionError)
                + System.lineSeparator() + "This is an infrastructure/framework problem (browser, "
                + "Playwright, or an unexpected bug) - not an application validation result."
                + System.lineSeparator() + "Result : " + resultJsonPath(result).toAbsolutePath();
    }

    private static String applicationValidationSummary(SoakResult result) {
        String failedPages = result.pageResults.entrySet().stream()
                .filter(entry -> !"PASS".equals(entry.getValue()) && !entry.getValue().startsWith("WARN"))
                .map(entry -> entry.getKey() + " [" + entry.getValue() + "]")
                .collect(Collectors.joining(System.lineSeparator() + "  - "));

        StringBuilder message = new StringBuilder();
        message.append("TEST EXECUTION COMPLETED SUCCESSFULLY. APPLICATION VALIDATION: FAIL")
                .append(System.lineSeparator())
                .append("[").append(result.executionId).append("] ").append(value(result.error));
        if (!failedPages.isBlank()) {
            message.append(System.lineSeparator()).append("Failed pages:").append(System.lineSeparator())
                    .append("  - ").append(failedPages);
        }
        message.append(System.lineSeparator()).append("Report : ")
                .append(SoakRunContext.current().finalReportDirectory().resolve("soak-test-report.html").toAbsolutePath())
                .append(System.lineSeparator()).append("Result : ").append(resultJsonPath(result).toAbsolutePath());
        return message.toString();
    }

    private static Path resultJsonPath(SoakResult result) {
        return Path.of(SoakTestConfig.load().outputDirectory(), result.executionId, "result.json");
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "N/A" : text;
    }
}
