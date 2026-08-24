package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.execution.ApiTestResult;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.inventory.CombinedInventoryBuilder.Built;
import com.vigilx.apisecurity.inventory.FinalApiRecord;
import com.vigilx.apisecurity.performance.JtlResultParser;
import com.vigilx.apisecurity.performance.LoadProfile;
import com.vigilx.apisecurity.restassured.RestApiTestRunner;
import com.vigilx.apisecurity.security.OwaspCategory;
import com.vigilx.apisecurity.security.OwaspSecurityTestRunner;
import com.vigilx.apisecurity.security.SecurityFinding;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;
import com.vigilx.apisecurity.zap.ZapScanRunner;

/**
 * Reorganizes the REAL result objects one execution run already produced (never re-executes
 * anything, never fabricates a result) into the folder tree requested for this run:
 * {@code target/security-test-report/}. Every existing runner/generator is reused as-is; this class
 * only reshapes their already-real output into a different, more granular layout.
 *
 * <p>Where the request's folder tree implies coverage that does not exist yet (e.g. a dedicated
 * negative-test engine applying all ~15 negative-test categories to every one of the 123 endpoints,
 * or CORS-specific checks), the corresponding file is written with an explicit note explaining the
 * real, current scope rather than inventing rows to fill the shape.
 */
public final class SecurityTestReportOrganizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SecurityTestReportOrganizer() {
    }

    public static void writeAll(Path root, Built inventory, RestApiTestRunner.RunOutcome positive,
                                OwaspSecurityTestRunner.RunResult owasp, ZapScanRunner.RunResult zap,
                                JmeterRunResult jmeter, List<String> blockedPhases) throws IOException {
        Files.createDirectories(root);

        List<ApiExecutionRollup> rollups = buildRollups(inventory.finalRecords, positive, owasp, zap, jmeter);

        writeInventorySection(root.resolve("api/inventory"), inventory);
        writePositiveSection(root.resolve("api/positive"), positive);
        writeNegativeSection(root.resolve("api/negative"), owasp);
        writeApiSummary(root.resolve("api"), rollups);
        writeOwaspSection(root.resolve("owasp"), owasp);
        writeZapSection(root.resolve("zap"), zap);
        writePerformanceSection(root.resolve("performance/jmeter"), jmeter);
        writeSecuritySection(root.resolve("security"), owasp);
        writeEvidenceSection(root.resolve("evidence"), zap, jmeter);
        writeFinalSection(root.resolve("final"), inventory, positive, owasp, zap, jmeter, rollups, blockedPhases);

        System.out.println("[SECURITY TEST REPORT] Full report tree written under " + root.toAbsolutePath());
    }

    /** Carries JMeter's already-real outputs through without re-running anything. */
    public static final class JmeterRunResult {
        public final boolean executed;
        public final String error;
        public final Path jmxFile;
        public final Path jtlFile;
        public final Path htmlReportDir;
        public final Path logFile;
        public final JtlResultParser.Summary summary;
        public final LoadProfile profile;

        public JmeterRunResult(boolean executed, String error, Path jmxFile, Path jtlFile, Path htmlReportDir,
                               Path logFile, JtlResultParser.Summary summary, LoadProfile profile) {
            this.executed = executed;
            this.error = error;
            this.jmxFile = jmxFile;
            this.jtlFile = jtlFile;
            this.htmlReportDir = htmlReportDir;
            this.logFile = logFile;
            this.summary = summary;
            this.profile = profile;
        }
    }

    // ---------------------------------------------------------------------
    // Per-API rollup (Phase 8/26)
    // ---------------------------------------------------------------------

    private static List<ApiExecutionRollup> buildRollups(List<FinalApiRecord> records,
                                                          RestApiTestRunner.RunOutcome positive,
                                                          OwaspSecurityTestRunner.RunResult owasp,
                                                          ZapScanRunner.RunResult zap, JmeterRunResult jmeter) {
        Map<String, ApiTestResult> positiveByKey = new HashMap<>();
        for (ApiTestResult result : positive.results) {
            positiveByKey.put(result.method() + "|" + result.host() + "|" + result.path(), result);
        }

        List<ApiExecutionRollup> rollups = new ArrayList<>();
        for (FinalApiRecord record : records) {
            ApiExecutionRollup rollup = new ApiExecutionRollup(record);
            ApiDefinition definition = record.definition();
            String positiveKey = definition.method() + "|" + definition.host() + "|" + definition.samplePath();
            ApiTestResult positiveResult = positiveByKey.get(positiveKey);

            if (!record.eligibleForFunctionalTesting()) {
                rollup.overallStatus = ApiExecutionRollup.Status.NOT_APPLICABLE_WITH_REASON;
                rollup.remarks = record.exclusionReason();
            } else if (positive.authFailed) {
                rollup.overallStatus = ApiExecutionRollup.Status.BLOCKED_WITH_REASON;
                rollup.remarks = "Positive testing blocked: " + positive.authFailureReason;
            } else if (positiveResult != null) {
                rollup.positivePlanned = 1;
                rollup.positiveExecuted = positiveResult.outcome() != ApiTestResult.Outcome.SKIPPED ? 1 : 0;
                rollup.positivePass = positiveResult.isPassed() ? 1 : 0;
                rollup.positiveFail = positiveResult.outcome() == ApiTestResult.Outcome.FAIL ? 1 : 0;
                rollup.overallStatus = positiveResult.outcome() == ApiTestResult.Outcome.SKIPPED
                        ? ApiExecutionRollup.Status.SKIPPED_WITH_REASON : ApiExecutionRollup.Status.TESTED;
                rollup.remarks = positiveResult.outcome() == ApiTestResult.Outcome.SKIPPED
                        ? "Circuit breaker: not reached this run" : "";
            } else {
                rollup.positivePlanned = 1;
                rollup.overallStatus = ApiExecutionRollup.Status.SKIPPED_WITH_REASON;
                rollup.remarks = "Eligible but no positive result recorded this run (e.g. capped by "
                        + "apisecurity.restassured.max.apis)";
            }

            // Negative/security: every OWASP finding tied to this API's normalized endpoint+method.
            if (!owasp.authFailed) {
                for (SecurityFinding finding : owasp.findings) {
                    Map<String, Object> map = finding.toJsonMap();
                    String endpoint = String.valueOf(map.get("endpoint"));
                    String method = String.valueOf(map.get("method"));
                    if (definition.normalizedPath().equals(endpoint) && definition.method().equals(method)) {
                        rollup.securityTestsExecuted++;
                        rollup.negativePlanned++;
                        rollup.owaspCategories.add(String.valueOf(map.get("owaspCategory")));
                        Verdict verdict = finding.verdict();
                        if (verdict != Verdict.NOT_ASSESSED) {
                            rollup.negativeExecuted++;
                            if (verdict == Verdict.PASS) {
                                rollup.negativePass++;
                            } else if (verdict == Verdict.FAIL) {
                                rollup.negativeFail++;
                                rollup.overallStatus = ApiExecutionRollup.Status.TESTED;
                            }
                        }
                    }
                }
            }

            rollup.zapTested = zap.daemonAvailable && !zap.authFailed && record.eligibleForSecurityTesting();
            rollup.jmeterTested = jmeter.executed && record.eligibleForPerformanceTesting()
                    && jmeter.summary.byLabel.containsKey(definition.method() + " " + definition.normalizedPath());

            rollups.add(rollup);
        }
        return rollups;
    }

    // ---------------------------------------------------------------------
    // api/inventory
    // ---------------------------------------------------------------------

    private static void writeInventorySection(Path dir, Built inventory) throws IOException {
        Files.createDirectories(dir);
        writeDefinitionList(dir.resolve("apimonitor-apis.json"), inventory.comparison.monitorApis());
        writeDefinitionList(dir.resolve("har-apis.json"), inventory.comparison.harApis());
        writeDefinitionList(dir.resolve("duplicate-apis.json"), inventory.comparison.duplicates());
        writeDefinitionList(dir.resolve("missing-apis.json"), inventory.comparison.missingFromMonitor());

        var array = JSON.createArrayNode();
        for (FinalApiRecord record : inventory.finalRecords) {
            array.addPOJO(record.toJsonMap());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("final-api-inventory.json").toFile(), array);
    }

    private static void writeDefinitionList(Path file, List<ApiDefinition> definitions) throws IOException {
        var array = JSON.createArrayNode();
        for (ApiDefinition definition : definitions) {
            array.addPOJO(definition.toJsonMap());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), array);
    }

    // ---------------------------------------------------------------------
    // api/positive
    // ---------------------------------------------------------------------

    private static void writePositiveSection(Path dir, RestApiTestRunner.RunOutcome positive) throws IOException {
        Files.createDirectories(dir);
        var array = JSON.createArrayNode();
        List<List<String>> rows = new ArrayList<>();
        for (ApiTestResult result : positive.results) {
            array.addPOJO(result.toJsonMap());
            rows.add(List.of(result.method(), SimpleHtmlPage.escape(result.path()),
                    String.valueOf(result.expectedStatus()), String.valueOf(result.actualStatus()),
                    result.responseTimeMs() + "ms", SimpleHtmlPage.badge(result.outcome().name())));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("positive-results.json").toFile(), array);
        writeCsvFromResults(dir.resolve("positive-results.csv"), positive.results);

        String body = summaryCards(
                "Executed", positive.results.size(),
                "Passed", count(positive.results, ApiTestResult.Outcome.PASS),
                "Failed", count(positive.results, ApiTestResult.Outcome.FAIL))
                + "<p class=\"note\">Positive testing is GET-only by policy (real auth, real captured "
                + "parameters, comparing against each endpoint's own observed expected status). Write "
                + "APIs (POST/PUT/PATCH/DELETE) are not exercised here - see api-summary.html for why "
                + "each one is excluded.</p>"
                + SimpleHtmlPage.table(List.of("Method", "Endpoint", "Expected", "Actual", "Time", "Result"), rows);
        Files.writeString(dir.resolve("positive-api-report.html"),
                SimpleHtmlPage.page("Positive API Test Report", body), StandardCharsets.UTF_8);
    }

    private static void writeCsvFromResults(Path file, List<ApiTestResult> results) throws IOException {
        StringBuilder csv = new StringBuilder("Method,Endpoint,ExpectedStatus,ActualStatus,ResponseTimeMs,Outcome,Note\n");
        for (ApiTestResult result : results) {
            Map<String, Object> map = result.toJsonMap();
            csv.append(csvField(map.get("method"))).append(',').append(csvField(map.get("path"))).append(',')
                    .append(map.get("expectedStatus")).append(',').append(map.get("actualStatus")).append(',')
                    .append(map.get("responseTimeMs")).append(',').append(csvField(map.get("outcome"))).append(',')
                    .append(csvField(map.get("note"))).append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    private static int count(List<ApiTestResult> results, ApiTestResult.Outcome outcome) {
        int total = 0;
        for (ApiTestResult result : results) {
            if (result.outcome() == outcome) {
                total++;
            }
        }
        return total;
    }

    // ---------------------------------------------------------------------
    // api/negative (the real negative-test-shaped OWASP probes: auth, BOLA, injection, resource)
    // ---------------------------------------------------------------------

    private static void writeNegativeSection(Path dir, OwaspSecurityTestRunner.RunResult owasp) throws IOException {
        Files.createDirectories(dir);
        var array = JSON.createArrayNode();
        List<List<String>> rows = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        int notAssessed = 0;
        for (SecurityFinding finding : owasp.findings) {
            Map<String, Object> map = finding.toJsonMap();
            array.addPOJO(map);
            rows.add(List.of(SimpleHtmlPage.escape(map.get("check")), SimpleHtmlPage.escape(map.get("method")),
                    SimpleHtmlPage.escape(map.get("endpoint")), SimpleHtmlPage.badge(String.valueOf(map.get("verdict"))),
                    SimpleHtmlPage.escape(map.get("severity")), SimpleHtmlPage.escape(map.get("summary"))));
            if ("PASS".equals(map.get("verdict"))) {
                pass++;
            } else if ("FAIL".equals(map.get("verdict"))) {
                fail++;
            } else {
                notAssessed++;
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("negative-results.json").toFile(), array);

        StringBuilder csv = new StringBuilder("Check,Method,Endpoint,Verdict,Severity,Summary\n");
        for (SecurityFinding finding : owasp.findings) {
            Map<String, Object> map = finding.toJsonMap();
            csv.append(csvField(map.get("check"))).append(',').append(csvField(map.get("method"))).append(',')
                    .append(csvField(map.get("endpoint"))).append(',').append(csvField(map.get("verdict"))).append(',')
                    .append(csvField(map.get("severity"))).append(',').append(csvField(map.get("summary"))).append('\n');
        }
        Files.writeString(dir.resolve("negative-results.csv"), csv.toString(), StandardCharsets.UTF_8);

        String body = (owasp.authFailed
                ? "<p class=\"note\"><strong>BLOCKED:</strong> " + SimpleHtmlPage.escape(owasp.authFailureReason) + "</p>"
                : summaryCards("Executed", owasp.findings.size() - notAssessed, "Passed", pass, "Failed", fail))
                + "<p class=\"note\">Real, executed negative-test-shaped probes: missing/invalid auth token, "
                + "one wrong-password login, a random-ID BOLA probe, XSS/SQLi-shaped query payloads, an "
                + "oversized pagination value, and a small rate-limit burst - applied to a representative "
                + "sample of endpoints per check, not yet a full per-API applicability matrix across all "
                + "APIs (that comprehensive negative-test engine is a separate, larger, not-yet-built piece "
                + "- see the final report's Limitations section). NOT_ASSESSED rows are real, deliberate "
                + "outcomes, never counted as PASS.</p>"
                + SimpleHtmlPage.table(List.of("Check", "Method", "Endpoint", "Verdict", "Severity", "Summary"), rows);
        Files.writeString(dir.resolve("negative-api-report.html"),
                SimpleHtmlPage.page("Negative API Test Report", body), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------
    // api/api-summary.html (per-API rollup, Phase 26)
    // ---------------------------------------------------------------------

    private static void writeApiSummary(Path dir, List<ApiExecutionRollup> rollups) throws IOException {
        Files.createDirectories(dir);
        var array = JSON.createArrayNode();
        List<List<String>> rows = new ArrayList<>();
        Map<String, Integer> statusCounts = new HashMap<>();
        for (ApiExecutionRollup rollup : rollups) {
            array.addPOJO(rollup.toJsonMap());
            statusCounts.merge(rollup.overallStatus.name(), 1, Integer::sum);
            rows.add(List.of(
                    rollup.record.apiId(), rollup.record.definition().method(),
                    SimpleHtmlPage.escape(rollup.record.definition().normalizedPath()),
                    rollup.record.source().name(),
                    rollup.positiveExecuted + "/" + rollup.positivePlanned,
                    rollup.positivePass + "/" + rollup.positiveFail,
                    rollup.negativeExecuted + "/" + rollup.negativePlanned,
                    rollup.negativePass + "/" + rollup.negativeFail,
                    rollup.zapTested ? "YES" : "NO", rollup.jmeterTested ? "YES" : "NO",
                    SimpleHtmlPage.badge(rollup.overallStatus.name()),
                    SimpleHtmlPage.escape(rollup.remarks)));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("api-execution-report.json").toFile(), array);

        StringBuilder cards = new StringBuilder("<div class=\"grid\">");
        for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
            cards.append(SimpleHtmlPage.card(entry.getKey(), entry.getValue(), ""));
        }
        cards.append("</div>");

        String body = cards
                + SimpleHtmlPage.table(List.of("API ID", "Method", "Endpoint", "Source", "Positive Exec/Planned",
                        "Positive Pass/Fail", "Negative Exec/Planned", "Negative Pass/Fail", "ZAP", "JMeter",
                        "Overall", "Remarks"), rows);
        Files.writeString(dir.resolve("api-execution-report.html"),
                SimpleHtmlPage.page("Per-API Execution Report", body), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------
    // owasp/api{N}-{name}/
    // ---------------------------------------------------------------------

    private static final Map<OwaspCategory, String> OWASP_FOLDER = Map.of(
            OwaspCategory.API1_BOLA, "api1-bola",
            OwaspCategory.API2_BROKEN_AUTHENTICATION, "api2-authentication",
            OwaspCategory.API3_BROKEN_PROPERTY_AUTHORIZATION, "api3-property-authorization",
            OwaspCategory.API4_RESOURCE_CONSUMPTION, "api4-resource-consumption",
            OwaspCategory.API5_BROKEN_FUNCTION_AUTHORIZATION, "api5-function-authorization",
            OwaspCategory.API6_SENSITIVE_BUSINESS_FLOWS, "api6-sensitive-business-flows",
            OwaspCategory.API7_SSRF, "api7-ssrf",
            OwaspCategory.API8_SECURITY_MISCONFIGURATION, "api8-security-misconfiguration",
            OwaspCategory.API9_IMPROPER_INVENTORY, "api9-inventory",
            OwaspCategory.API10_UNSAFE_CONSUMPTION, "api10-unsafe-api-consumption");

    private static void writeOwaspSection(Path dir, OwaspSecurityTestRunner.RunResult owasp) throws IOException {
        Files.createDirectories(dir);
        List<List<String>> summaryRows = new ArrayList<>();

        for (OwaspCategory category : OwaspCategory.values()) {
            Path categoryDir = dir.resolve(OWASP_FOLDER.get(category));
            Files.createDirectories(categoryDir);

            List<SecurityFinding> categoryFindings = new ArrayList<>();
            for (SecurityFinding finding : owasp.findings) {
                if (finding.category() == category) {
                    categoryFindings.add(finding);
                }
            }

            var array = JSON.createArrayNode();
            List<List<String>> rows = new ArrayList<>();
            int pass = 0;
            int fail = 0;
            int notAssessed = 0;
            for (SecurityFinding finding : categoryFindings) {
                Map<String, Object> map = finding.toJsonMap();
                array.addPOJO(map);
                rows.add(List.of(SimpleHtmlPage.escape(map.get("check")), SimpleHtmlPage.escape(map.get("endpoint")),
                        SimpleHtmlPage.badge(String.valueOf(map.get("verdict"))),
                        SimpleHtmlPage.escape(map.get("severity")), SimpleHtmlPage.escape(map.get("summary"))));
                switch (finding.verdict()) {
                    case PASS -> pass++;
                    case FAIL -> fail++;
                    default -> notAssessed++;
                }
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(categoryDir.resolve("results.json").toFile(), array);

            String status = owasp.authFailed ? "BLOCKED" : categoryFindings.isEmpty() ? "NOT_ASSESSED"
                    : fail > 0 ? "FAIL/FINDING" : pass > 0 ? "PASS" : "NOT_ASSESSED";

            String body = summaryCards("Test Cases", categoryFindings.size(), "Pass", pass, "Fail/Finding", fail)
                    + SimpleHtmlPage.card("Not Assessed", notAssessed, "warn")
                    + SimpleHtmlPage.table(List.of("Check", "Endpoint", "Verdict", "Severity", "Summary"), rows);
            Files.writeString(categoryDir.resolve("report.html"),
                    SimpleHtmlPage.page(category.id() + " - " + category.title(), body), StandardCharsets.UTF_8);

            summaryRows.add(List.of(category.id(), category.title(), SimpleHtmlPage.badge(status),
                    String.valueOf(categoryFindings.size()), String.valueOf(pass), String.valueOf(fail),
                    String.valueOf(notAssessed)));
        }

        String summaryBody = (owasp.authFailed
                ? "<p class=\"note\"><strong>BLOCKED:</strong> " + SimpleHtmlPage.escape(owasp.authFailureReason) + "</p>"
                : "")
                + SimpleHtmlPage.table(List.of("Category", "Title", "Status", "Test Cases", "Pass", "Fail", "N/A"), summaryRows);
        Files.writeString(dir.resolve("owasp-summary.html"),
                SimpleHtmlPage.page("OWASP API Security Top 10 Summary", summaryBody), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------
    // zap/
    // ---------------------------------------------------------------------

    private static void writeZapSection(Path dir, ZapScanRunner.RunResult zap) throws IOException {
        Files.createDirectories(dir.resolve("raw"));
        Files.createDirectories(dir.resolve("alerts"));
        Files.createDirectories(dir.resolve("evidence"));

        var array = JSON.createArrayNode();
        List<List<String>> rows = new ArrayList<>();
        Map<String, Integer> riskCounts = new HashMap<>();
        for (Map<String, Object> alert : zap.alerts) {
            array.addPOJO(alert);
            String risk = String.valueOf(alert.getOrDefault("risk", "Unknown"));
            riskCounts.merge(risk, 1, Integer::sum);
            rows.add(List.of(SimpleHtmlPage.escape(risk), SimpleHtmlPage.escape(alert.get("confidence")),
                    SimpleHtmlPage.escape(alert.get("alert")), SimpleHtmlPage.escape(alert.get("url")),
                    SimpleHtmlPage.escape(alert.get("param"))));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("alerts/alerts.json").toFile(), array);
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("zap-report.json").toFile(), array);

        String status = !zap.daemonAvailable ? "BLOCKED: ZAP daemon not available"
                : zap.authFailed ? "BLOCKED: " + zap.authFailureReason : "COMPLETED";

        StringBuilder cards = new StringBuilder("<div class=\"grid\">");
        cards.append(SimpleHtmlPage.card("URLs Scanned", zap.urlsFedToPassiveScan, ""));
        cards.append(SimpleHtmlPage.card("Alerts", zap.alerts.size(), zap.alerts.isEmpty() ? "pass" : "warn"));
        for (Map.Entry<String, Integer> entry : riskCounts.entrySet()) {
            cards.append(SimpleHtmlPage.card(entry.getKey(), entry.getValue(), ""));
        }
        cards.append("</div>");

        String body = "<p class=\"note\">Status: " + SimpleHtmlPage.escape(status) + " | Active scan: "
                + (zap.activeScanEnabled ? "ENABLED (" + zap.urlsActivelyScanned + " URLs)" : "disabled (default)")
                + "</p>" + cards + SimpleHtmlPage.table(List.of("Risk", "Confidence", "Alert", "URL", "Param"), rows);
        Files.writeString(dir.resolve("zap-report.html"), SimpleHtmlPage.page("OWASP ZAP Scan Report", body),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("zap-summary.html"), SimpleHtmlPage.page("ZAP Summary", body), StandardCharsets.UTF_8);

        // "Raw" output: the daemon's own log, if this run produced one.
        Path daemonLog = Path.of("target/apisecurity/reports/zap-daemon.log");
        if (Files.exists(daemonLog)) {
            Files.copy(daemonLog, dir.resolve("raw/zap-daemon.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.writeString(dir.resolve("raw/README.txt"),
                    "No raw ZAP daemon log was produced by this run (daemon was already running).");
        }
    }

    // ---------------------------------------------------------------------
    // performance/jmeter/
    // ---------------------------------------------------------------------

    private static void writePerformanceSection(Path dir, JmeterRunResult jmeter) throws IOException {
        Path scenarios = dir.resolve("scenarios");
        Path jtl = dir.resolve("jtl");
        Path html = dir.resolve("html");
        Path logs = dir.resolve("logs");
        Files.createDirectories(scenarios);
        Files.createDirectories(jtl);
        Files.createDirectories(logs);
        Files.createDirectories(dir);

        if (!jmeter.executed) {
            Files.writeString(dir.resolve("jmeter-summary.html"),
                    SimpleHtmlPage.page("JMeter Performance Summary",
                            "<p class=\"note\"><strong>BLOCKED:</strong> " + SimpleHtmlPage.escape(jmeter.error) + "</p>"),
                    StandardCharsets.UTF_8);
            return;
        }

        copyIfExists(jmeter.jmxFile, scenarios.resolve(jmeter.jmxFile.getFileName()));
        copyIfExists(jmeter.jtlFile, jtl.resolve(jmeter.jtlFile.getFileName()));
        copyIfExists(jmeter.logFile, logs.resolve(jmeter.logFile.getFileName()));
        copyDirIfExists(jmeter.htmlReportDir, html);

        JtlResultParser.Summary summary = jmeter.summary;
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, JtlResultParser.LabelSummary> entry : summary.byLabel.entrySet()) {
            JtlResultParser.LabelSummary label = entry.getValue();
            rows.add(List.of(SimpleHtmlPage.escape(entry.getKey()), String.valueOf(label.samples),
                    String.valueOf(label.errors), String.format("%.0f", label.avgMs), String.format("%.0f", label.p95Ms)));
        }

        String body = "<div class=\"grid\">"
                + SimpleHtmlPage.card("Users", jmeter.profile.users(), "")
                + SimpleHtmlPage.card("Ramp-up (s)", jmeter.profile.rampUpSeconds(), "")
                + SimpleHtmlPage.card("Duration (s)", jmeter.profile.durationSeconds(), "")
                + SimpleHtmlPage.card("Total Requests", summary.totalSamples, "")
                + SimpleHtmlPage.card("Failures", summary.errorCount, summary.errorCount > 0 ? "warn" : "pass")
                + SimpleHtmlPage.card("Error %", String.format(Locale.ROOT, "%.2f", summary.errorRatePercent), "")
                + SimpleHtmlPage.card("Throughput/s", String.format(Locale.ROOT, "%.2f", summary.throughputPerSecond), "")
                + SimpleHtmlPage.card("Min (ms)", (int) summary.minMs, "")
                + SimpleHtmlPage.card("Avg (ms)", (int) summary.avgMs, "")
                + SimpleHtmlPage.card("P90 (ms)", (int) summary.p90Ms, "")
                + SimpleHtmlPage.card("P95 (ms)", (int) summary.p95Ms, "")
                + SimpleHtmlPage.card("P99 (ms)", (int) summary.p99Ms, "")
                + SimpleHtmlPage.card("Max (ms)", (int) summary.maxMs, "")
                + "</div>"
                + "<p class=\"note\">JMeter's own error count treats any HTTP&nbsp;&ge;400 as an error, even "
                + "where that is an endpoint's own documented expected status - cross-check against "
                + "api/inventory/final-api-inventory.json before treating a JMeter \"error\" as a regression. "
                + "JMeter's own dashboard: html/index.html</p>"
                + SimpleHtmlPage.table(List.of("Endpoint", "Samples", "Errors", "Avg (ms)", "P95 (ms)"), rows);
        Files.writeString(dir.resolve("jmeter-summary.html"),
                SimpleHtmlPage.page("JMeter Performance Summary", body), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------
    // security/ (best-effort mapping of real OWASP findings onto the requested sub-taxonomy)
    // ---------------------------------------------------------------------

    private static void writeSecuritySection(Path dir, OwaspSecurityTestRunner.RunResult owasp) throws IOException {
        writeSecuritySubfolder(dir.resolve("authentication"), owasp, "Missing token", "Invalid", "credentials");
        writeSecuritySubfolder(dir.resolve("authorization"), owasp, "BOLA", "Random-ID", "cross-tenant");
        writeSecuritySubfolder(dir.resolve("input-validation"), owasp, "Oversized", "boundary", "limit");
        writeSecuritySubfolder(dir.resolve("injection"), owasp, "XSS", "SQLi", "payload");
        writeSecuritySubfolder(dir.resolve("rate-limit"), owasp, "Rate limiting", "burst", "429");
        writeSecuritySubfolder(dir.resolve("headers"), owasp, "security headers", "stack trace", "header");
        writeCorsNote(dir.resolve("cors"));

        StringBuilder body = new StringBuilder();
        body.append("<p class=\"note\">Each subfolder contains the real OWASP findings whose check name "
                + "matched that category (see negative-api-report.html for the complete, unfiltered list). "
                + "\"cors\" has no dedicated check yet - a real limitation, not a fabricated result.</p>");
        Files.writeString(dir.resolve("security-summary.html"),
                SimpleHtmlPage.page("Security Testing Summary", body.toString()), StandardCharsets.UTF_8);
    }

    private static void writeSecuritySubfolder(Path dir, OwaspSecurityTestRunner.RunResult owasp,
                                               String... keywordMatches) throws IOException {
        Files.createDirectories(dir.resolve("positive"));
        Files.createDirectories(dir.resolve("negative"));
        List<SecurityFinding> matched = new ArrayList<>();
        for (SecurityFinding finding : owasp.findings) {
            String checkName = String.valueOf(finding.toJsonMap().get("check")).toLowerCase(Locale.ROOT);
            for (String keyword : keywordMatches) {
                if (checkName.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matched.add(finding);
                    break;
                }
            }
        }
        var array = JSON.createArrayNode();
        for (SecurityFinding finding : matched) {
            array.addPOJO(finding.toJsonMap());
        }
        // These probes are inherently negative (expecting a rejection); real "positive" security cases
        // (e.g. a valid authenticated request succeeding) are already covered under api/positive/.
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("negative/results.json").toFile(), array);
        Files.writeString(dir.resolve("positive/README.txt"),
                "Positive (valid-request) cases for this OWASP-derived category are covered under "
                        + "api/positive/ rather than duplicated here.");
    }

    private static void writeCorsNote(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("README.txt"),
                "No dedicated CORS check exists in this run - SecurityMisconfigurationCheck validates "
                        + "baseline security headers (HSTS, X-Content-Type-Options, X-Frame-Options) but does "
                        + "not yet assert on Access-Control-Allow-Origin behavior specifically. Real gap, not "
                        + "a fabricated PASS.");
    }

    // ---------------------------------------------------------------------
    // evidence/
    // ---------------------------------------------------------------------

    private static void writeEvidenceSection(Path dir, ZapScanRunner.RunResult zap, JmeterRunResult jmeter)
            throws IOException {
        Files.createDirectories(dir.resolve("screenshots"));
        Files.createDirectories(dir.resolve("request-response"));
        Files.createDirectories(dir.resolve("logs"));

        Files.writeString(dir.resolve("screenshots/README.txt"),
                "Not applicable: this is a pure API-testing module (REST Assured/OWASP/ZAP/JMeter), it "
                        + "never drives a browser, so it has no UI to screenshot. Existing SOAK screenshots "
                        + "remain exactly where they always were, untouched.");

        if (jmeter.logFile != null && Files.exists(jmeter.logFile)) {
            Files.copy(jmeter.logFile, dir.resolve("logs").resolve(jmeter.logFile.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path daemonLog = Path.of("target/apisecurity/reports/zap-daemon.log");
        if (Files.exists(daemonLog)) {
            Files.copy(daemonLog, dir.resolve("logs/zap-daemon.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        Files.writeString(dir.resolve("request-response/README.txt"),
                "Masked evidence for every FAIL/FINDING is already embedded in each finding's \"evidence\" "
                        + "field - see owasp/*/results.json and api/negative/negative-results.json.");
    }

    // ---------------------------------------------------------------------
    // final/
    // ---------------------------------------------------------------------

    private static void writeFinalSection(Path dir, Built inventory, RestApiTestRunner.RunOutcome positive,
                                          OwaspSecurityTestRunner.RunResult owasp, ZapScanRunner.RunResult zap,
                                          JmeterRunResult jmeter, List<ApiExecutionRollup> rollups,
                                          List<String> blockedPhases) throws IOException {
        Files.createDirectories(dir);

        int owaspPass = 0;
        int owaspFail = 0;
        for (SecurityFinding finding : owasp.findings) {
            if (finding.verdict() == Verdict.PASS) {
                owaspPass++;
            } else if (finding.verdict() == Verdict.FAIL) {
                owaspFail++;
            }
        }
        Map<String, Integer> riskCounts = new HashMap<>();
        for (Map<String, Object> alert : zap.alerts) {
            riskCounts.merge(String.valueOf(alert.getOrDefault("risk", "Unknown")), 1, Integer::sum);
        }
        Map<ApiExecutionRollup.Status, Integer> coverage = new HashMap<>();
        for (ApiExecutionRollup rollup : rollups) {
            coverage.merge(rollup.overallStatus, 1, Integer::sum);
        }

        var json = JSON.createObjectNode();
        json.put("generatedAt", LocalDateTime.now().format(TIMESTAMP));
        json.put("apiMonitorApis", inventory.comparison.monitorApis().size());
        json.put("harApis", inventory.comparison.harApis().size());
        json.put("duplicateApis", inventory.comparison.duplicates().size());
        json.put("missingApis", inventory.comparison.missingFromMonitor().size());
        json.put("finalUniqueApis", inventory.comparison.combinedUnique().size());
        json.put("positiveExecuted", positive.results.size());
        json.put("positivePassed", count(positive.results, ApiTestResult.Outcome.PASS));
        json.put("positiveFailed", count(positive.results, ApiTestResult.Outcome.FAIL));
        json.put("negativeExecuted", owasp.findings.size());
        json.put("negativePassed", owaspPass);
        json.put("negativeFailed", owaspFail);
        json.put("zapAlerts", zap.alerts.size());
        json.put("jmeterRequests", jmeter.executed ? jmeter.summary.totalSamples : 0);
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("consolidated-report.json").toFile(), json);

        StringBuilder csv = new StringBuilder("Metric,Value\n");
        json.properties().forEach(entry -> csv.append(csvField(entry.getKey())).append(',')
                .append(csvField(entry.getValue().asText())).append('\n'));
        Files.writeString(dir.resolve("consolidated-report.csv"), csv.toString(), StandardCharsets.UTF_8);

        String html = buildFinalHtml(inventory, positive, owasp, zap, jmeter, owaspPass, owaspFail, riskCounts,
                coverage, blockedPhases);
        Files.writeString(dir.resolve("consolidated-report.html"), html, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("execution-summary.html"), html, StandardCharsets.UTF_8);
    }

    private static String buildFinalHtml(Built inventory, RestApiTestRunner.RunOutcome positive,
                                         OwaspSecurityTestRunner.RunResult owasp, ZapScanRunner.RunResult zap,
                                         JmeterRunResult jmeter, int owaspPass, int owaspFail,
                                         Map<String, Integer> riskCounts, Map<ApiExecutionRollup.Status, Integer> coverage,
                                         List<String> blockedPhases) {
        StringBuilder body = new StringBuilder();
        body.append("<p class=\"sub\">Generated ").append(LocalDateTime.now().format(TIMESTAMP))
                .append(" &middot; environment: ").append(inventory.finalRecords.isEmpty() ? "unknown"
                        : inventory.finalRecords.get(0).definition().host())
                .append(" &middot; separate from, and never affecting, the existing SOAK suite</p>");

        if (!blockedPhases.isEmpty()) {
            body.append("<h2>Blocked Phases</h2><p class=\"note\">");
            for (String reason : blockedPhases) {
                body.append(SimpleHtmlPage.escape(reason)).append("<br>");
            }
            body.append("</p>");
        }

        body.append("<h2>1-8. API Discovery, Comparison, Missing APIs, Final Inventory</h2><div class=\"grid\">")
                .append(SimpleHtmlPage.card("ApiMonitor APIs", inventory.comparison.monitorApis().size(), ""))
                .append(SimpleHtmlPage.card("HAR APIs", inventory.comparison.harApis().size(), ""))
                .append(SimpleHtmlPage.card("Duplicates", inventory.comparison.duplicates().size(), ""))
                .append(SimpleHtmlPage.card("Missing APIs", inventory.comparison.missingFromMonitor().size(), "warn"))
                .append(SimpleHtmlPage.card("Final Unique APIs", inventory.comparison.combinedUnique().size(), "pass"))
                .append("</div>");

        body.append("<h2>9. Positive API Testing</h2>")
                .append(positive.authFailed
                        ? "<p class=\"note\"><strong>BLOCKED:</strong> " + SimpleHtmlPage.escape(positive.authFailureReason) + "</p>"
                        : "<div class=\"grid\">"
                                + SimpleHtmlPage.card("Planned/Executed", positive.results.size(), "")
                                + SimpleHtmlPage.card("Passed", count(positive.results, ApiTestResult.Outcome.PASS), "pass")
                                + SimpleHtmlPage.card("Failed", count(positive.results, ApiTestResult.Outcome.FAIL), "fail")
                                + SimpleHtmlPage.card("Not Auto-Executed", positive.notEligible.size(), "")
                                + "</div>");

        body.append("<h2>10-11. Negative &amp; Security Testing</h2>")
                .append(owasp.authFailed
                        ? "<p class=\"note\"><strong>BLOCKED:</strong> " + SimpleHtmlPage.escape(owasp.authFailureReason) + "</p>"
                        : "<div class=\"grid\">"
                                + SimpleHtmlPage.card("Executed", owasp.findings.size(), "")
                                + SimpleHtmlPage.card("Passed", owaspPass, "pass")
                                + SimpleHtmlPage.card("Failed/Findings", owaspFail, owaspFail > 0 ? "fail" : "pass")
                                + "</div>")
                .append("<p class=\"note\">Scope: representative negative-test-shaped probes per check "
                        + "(auth, BOLA, injection, resource consumption) - not yet a full per-API applicability "
                        + "matrix. See api/negative/negative-api-report.html.</p>");

        body.append("<h2>12. OWASP API Security Top 10</h2><table><thead><tr><th>Category</th><th>Title</th>"
                + "<th>Status</th></tr></thead><tbody>");
        for (OwaspCategory category : OwaspCategory.values()) {
            List<SecurityFinding> categoryFindings = owasp.findings.stream()
                    .filter(f -> f.category() == category).toList();
            String status = owasp.authFailed ? "BLOCKED" : categoryFindings.isEmpty() ? "NOT_ASSESSED"
                    : categoryFindings.stream().anyMatch(f -> f.verdict() == Verdict.FAIL) ? "FAIL/FINDING"
                    : categoryFindings.stream().anyMatch(f -> f.verdict() == Verdict.PASS) ? "PASS" : "NOT_ASSESSED";
            body.append("<tr><td>").append(category.id()).append("</td><td>").append(category.title())
                    .append("</td><td>").append(SimpleHtmlPage.badge(status)).append("</td></tr>");
        }
        body.append("</tbody></table>");

        body.append("<h2>13. OWASP ZAP</h2>")
                .append(!zap.daemonAvailable ? "<p class=\"note\"><strong>BLOCKED:</strong> ZAP daemon not available</p>"
                        : zap.authFailed ? "<p class=\"note\"><strong>BLOCKED:</strong> " + SimpleHtmlPage.escape(zap.authFailureReason) + "</p>"
                        : "<div class=\"grid\">" + SimpleHtmlPage.card("URLs Scanned", zap.urlsFedToPassiveScan, "")
                                + SimpleHtmlPage.card("Alerts", zap.alerts.size(), zap.alerts.isEmpty() ? "pass" : "warn"));
        for (Map.Entry<String, Integer> entry : riskCounts.entrySet()) {
            body.append(SimpleHtmlPage.card(entry.getKey(), entry.getValue(), ""));
        }
        body.append("</div>");

        body.append("<h2>14. JMeter Performance</h2>")
                .append(!jmeter.executed ? "<p class=\"note\"><strong>BLOCKED:</strong> " + SimpleHtmlPage.escape(jmeter.error) + "</p>"
                        : "<div class=\"grid\">"
                                + SimpleHtmlPage.card("Requests", jmeter.summary.totalSamples, "")
                                + SimpleHtmlPage.card("Failures", jmeter.summary.errorCount, "")
                                + SimpleHtmlPage.card("Error %", String.format(Locale.ROOT, "%.2f", jmeter.summary.errorRatePercent), "")
                                + SimpleHtmlPage.card("Throughput/s", String.format(Locale.ROOT, "%.2f", jmeter.summary.throughputPerSecond), "")
                                + SimpleHtmlPage.card("P95 (ms)", (int) jmeter.summary.p95Ms, "")
                                + SimpleHtmlPage.card("P99 (ms)", (int) jmeter.summary.p99Ms, "")
                                + "</div><p class=\"note\">JMeter's raw error count includes endpoints whose "
                                + "own documented expected status is non-2xx - cross-check before treating as a regression.</p>");

        body.append("<h2>18. Coverage</h2><div class=\"grid\">");
        for (Map.Entry<ApiExecutionRollup.Status, Integer> entry : coverage.entrySet()) {
            body.append(SimpleHtmlPage.card(entry.getKey().name(), entry.getValue(), ""));
        }
        body.append("</div>");

        body.append("<h2>19. Limitations (real, not hidden)</h2><ul>")
                .append("<li>Negative testing covers representative probes per OWASP check, not a full "
                        + "per-API applicability matrix across all ~15 negative-test categories.</li>")
                .append("<li>API5 (Function-Level Auth) and cross-tenant API1 (BOLA) require a second, "
                        + "lower-privileged test account - not configured, honestly NOT_ASSESSED.</li>")
                .append("<li>API6 (sensitive business flows) and API10 (unsafe upstream consumption) are "
                        + "NOT_ASSESSED - real side effects or downstream visibility this framework lacks.</li>")
                .append("<li>No dedicated CORS check exists yet.</li>")
                .append("<li>CREATE/UPDATE/DELETE APIs are never auto-executed (GET-only policy) - listed, "
                        + "not invoked, to avoid mutating real data.</li>")
                .append("</ul>");

        body.append("<h2>20. Final Summary</h2><div class=\"grid\">")
                .append(SimpleHtmlPage.card("Positive Pass Rate", positive.results.isEmpty() ? "n/a"
                        : (100 * count(positive.results, ApiTestResult.Outcome.PASS) / positive.results.size()) + "%", ""))
                .append(SimpleHtmlPage.card("Overall", blockedPhases.isEmpty() ? "COMPLETED" : "PARTIALLY BLOCKED",
                        blockedPhases.isEmpty() ? "pass" : "warn"))
                .append("</div>");

        return SimpleHtmlPage.page("VigilX API Security & Performance - Consolidated Report", body.toString());
    }

    // ---------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------

    private static String summaryCards(Object... labelValuePairs) {
        StringBuilder html = new StringBuilder("<div class=\"grid\">");
        for (int i = 0; i < labelValuePairs.length; i += 2) {
            html.append(SimpleHtmlPage.card(String.valueOf(labelValuePairs[i]), labelValuePairs[i + 1], ""));
        }
        html.append("</div>");
        return html.toString();
    }

    private static void copyIfExists(Path source, Path target) throws IOException {
        if (source != null && Files.exists(source)) {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyDirIfExists(Path source, Path target) throws IOException {
        if (source == null || !Files.exists(source)) {
            return;
        }
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String csvField(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains(",") || text.contains("\"")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
