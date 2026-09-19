package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.performance.LoadProfile;
import com.vigilx.apisecurity.performance.LoadTestJmxValidator.ValidationResult;
import com.vigilx.apisecurity.performance.LoadTestResultAnalyzer.AnalysisResult;
import com.vigilx.apisecurity.performance.LoadTestResultAnalyzer.ApiResult;
import com.vigilx.apisecurity.performance.LoadTestResultAnalyzer.CrudLifecycleResult;
import com.vigilx.apisecurity.performance.LoadTestResultAnalyzer.CrudStepResult;
import com.vigilx.apisecurity.performance.LoadTestResultAnalyzer.SampleResult;
import com.vigilx.apisecurity.performance.LoadTestResultAnalyzer.SkippedApi;

/**
 * Writes the load-test run's machine-readable results ({@code load-test-results.csv}/{@code .json})
 * and the human-readable {@code VigilX_API_Load_Test_Report.html}, entirely from the real
 * {@link AnalysisResult} already computed from the actual {@code .jtl} - never fabricated, and every
 * number here traces back to a real sample JMeter actually recorded.
 */
public final class LoadTestReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private LoadTestReportGenerator() {
    }

    public static void writeResults(Path resultsDirectory, AnalysisResult analysis) throws IOException {
        Files.createDirectories(resultsDirectory);
        writeCsv(resultsDirectory.resolve("load-test-results.csv"), analysis);
        writeJson(resultsDirectory.resolve("load-test-results.json"), analysis);
    }

    public static void writeHtmlReport(Path reportsDirectory, String testPlanName, LoadProfile profile,
                                       AnalysisResult analysis, ValidationResult jmxValidation,
                                       Path jmxFile, long durationMs) throws IOException {
        Files.createDirectories(reportsDirectory);
        Path file = reportsDirectory.resolve("VigilX_API_Load_Test_Report.html");
        Files.writeString(file, renderHtml(testPlanName, profile, analysis, jmxValidation, jmxFile, durationMs),
                StandardCharsets.UTF_8);
        System.out.println("[LOAD TEST REPORT] HTML report written to " + file);
    }

    // ---------------------------------------------------------------------
    // CSV / JSON (machine-readable)
    // ---------------------------------------------------------------------

    private static void writeCsv(Path file, AnalysisResult analysis) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("API,Method,Requests,Passed,Failed,PassPercent,FailPercent,AvgMs,MinMs,MaxMs,"
                + "ThroughputPerSecond,ExpectedStatus,StatusCodesSeen\n");
        for (ApiResult api : analysis.distinctApiResults()) {
            csv.append(csvField(api.endpoint)).append(',')
                    .append(csvField(api.method)).append(',')
                    .append(api.requests).append(',')
                    .append(api.passed).append(',')
                    .append(api.failed).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", api.passPercent())).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", api.failPercent())).append(',')
                    .append(String.format(Locale.ROOT, "%.0f", api.avgMs())).append(',')
                    .append(String.format(Locale.ROOT, "%.0f", api.minMs)).append(',')
                    .append(String.format(Locale.ROOT, "%.0f", api.maxMs)).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", api.throughputPerSecond())).append(',')
                    .append(api.expectedStatusKnown ? String.valueOf(api.expectedStatus) : "unknown").append(',')
                    .append(csvField(statusCodesText(api))).append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("[LOAD TEST REPORT] CSV written to " + file);
    }

    private static void writeJson(Path file, AnalysisResult analysis) throws IOException {
        var root = JSON.createObjectNode();
        root.put("totalApisCaptured", analysis.totalCaptured);
        root.put("totalApisIncludedInLoadTest", analysis.totalIncluded());
        root.put("totalApisExecuted", analysis.totalExecuted());
        root.put("totalApisSkipped", analysis.totalSkipped());
        root.put("totalApisTested", analysis.totalIncluded());
        root.put("totalRequests", analysis.totalRequests);
        root.put("passedRequests", analysis.totalPassed);
        root.put("failedRequests", analysis.totalFailed);
        root.put("passPercent", analysis.passPercent());
        root.put("failPercent", analysis.failPercent());
        root.put("startTime", formatTimestamp(analysis.startTimestampMs));
        root.put("endTime", formatTimestamp(analysis.endTimestampMs));

        var skippedNode = JSON.createArrayNode();
        for (SkippedApi skipped : analysis.skipped) {
            var node = JSON.createObjectNode();
            node.put("api", skipped.endpoint);
            node.put("method", skipped.method);
            node.put("reason", skipped.reason);
            skippedNode.add(node);
        }
        root.putPOJO("skippedApis", skippedNode);

        var crudNode = JSON.createArrayNode();
        for (CrudLifecycleResult lifecycle : analysis.crudLifecycles) {
            var node = JSON.createObjectNode();
            node.put("resource", lifecycle.resourceName);
            node.put("capturedIdVariable", lifecycle.capturedId);
            node.putPOJO("create", crudStepJson(lifecycle.create));
            node.putPOJO("update", crudStepJson(lifecycle.update));
            node.putPOJO("delete", crudStepJson(lifecycle.delete));
            crudNode.add(node);
        }
        root.putPOJO("crudLifecycles", crudNode);

        var apis = JSON.createArrayNode();
        for (ApiResult api : analysis.distinctApiResults()) {
            var node = JSON.createObjectNode();
            node.put("api", api.endpoint);
            node.put("method", api.method);
            node.put("requests", api.requests);
            node.put("passed", api.passed);
            node.put("failed", api.failed);
            node.put("passPercent", api.passPercent());
            node.put("failPercent", api.failPercent());
            node.put("avgMs", api.avgMs());
            node.put("minMs", api.minMs);
            node.put("maxMs", api.maxMs);
            node.put("throughputPerSecond", api.throughputPerSecond());
            node.put("expectedStatus", api.expectedStatusKnown ? String.valueOf(api.expectedStatus) : "unknown");
            var statusCodes = JSON.createObjectNode();
            for (Map.Entry<Integer, Integer> entry : api.statusCodeCounts.entrySet()) {
                statusCodes.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            node.putPOJO("statusCodesSeen", statusCodes);

            var failures = JSON.createArrayNode();
            for (SampleResult failure : api.failures) {
                var failureNode = JSON.createObjectNode();
                failureNode.put("method", failure.method);
                failureNode.put("endpoint", failure.endpoint);
                failureNode.put("expectedStatus", failure.expectedStatusKnown ? String.valueOf(failure.expectedStatus) : "unknown");
                failureNode.put("actualStatus", failure.actualStatus);
                failureNode.put("elapsedMs", failure.elapsedMs);
                failureNode.put("timestamp", formatTimestamp(failure.timestampMs));
                failureNode.put("reason", failure.failureMessage);
                failures.add(failureNode);
            }
            node.putPOJO("failures", failures);
            apis.add(node);
        }
        root.putPOJO("apis", apis);

        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        System.out.println("[LOAD TEST REPORT] JSON written to " + file);
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode crudStepJson(CrudStepResult step) {
        var node = JSON.createObjectNode();
        if (step == null) {
            node.put("status", "NOT CAPTURED");
            node.put("reason", "Not captured for this resource in the inventory");
            return node;
        }
        node.put("method", step.method);
        node.put("endpoint", step.endpoint);
        node.put("status", step.status);
        if (step.reason != null) {
            node.put("reason", step.reason);
        }
        return node;
    }

    // ---------------------------------------------------------------------
    // HTML (human-readable)
    // ---------------------------------------------------------------------

    private static String renderHtml(String testPlanName, LoadProfile profile, AnalysisResult analysis,
                                     ValidationResult jmxValidation, Path jmxFile, long durationMs) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>")
                .append(escapeHtml(testPlanName)).append(" Report</title>");
        html.append("<style>");
        html.append("body{font-family:Segoe UI,Arial,sans-serif;margin:24px;color:#1a1a1a;background:#fafafa}");
        html.append("h1{margin-bottom:4px}h2{margin-top:32px;border-bottom:2px solid #ddd;padding-bottom:6px}");
        html.append(".summary{display:flex;flex-wrap:wrap;gap:16px;margin:16px 0}");
        html.append(".card{background:#fff;border:1px solid #e0e0e0;border-radius:8px;padding:14px 18px;min-width:150px;box-shadow:0 1px 3px rgba(0,0,0,.06)}");
        html.append(".card .label{font-size:12px;color:#666;text-transform:uppercase}");
        html.append(".card .value{font-size:24px;font-weight:600}");
        html.append(".pass{color:#1a7f37}.fail{color:#c62828}");
        html.append("table{border-collapse:collapse;width:100%;background:#fff;margin-top:8px}");
        html.append("th,td{border:1px solid #e0e0e0;padding:8px 10px;font-size:13px;text-align:left}");
        html.append("th{background:#f0f2f5}");
        html.append("tr.fail-row{background:#fff3f3}tr.pass-row{background:#f4fbf5}");
        html.append(".badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:600}");
        html.append(".badge-pass{background:#e6f4ea;color:#1a7f37}.badge-fail{background:#fdecea;color:#c62828}");
        html.append(".jmx-ok{color:#1a7f37}.jmx-bad{color:#c62828}");
        html.append("</style></head><body>");

        html.append("<h1>").append(escapeHtml(testPlanName)).append("</h1>");
        html.append("<p>API Load Test Report - generated ").append(TIMESTAMP.format(Instant.now())).append("</p>");

        html.append("<h2>Test Summary</h2><div class=\"summary\">");
        appendCard(html, "Total APIs Captured", String.valueOf(analysis.totalCaptured), null);
        appendCard(html, "Included in Load Test", String.valueOf(analysis.totalIncluded()), null);
        appendCard(html, "Total APIs Executed", String.valueOf(analysis.totalExecuted()), null);
        appendCard(html, "Total APIs Skipped", String.valueOf(analysis.totalSkipped()),
                analysis.totalSkipped() > 0 ? "fail" : "pass");
        appendCard(html, "Total Requests", String.valueOf(analysis.totalRequests), null);
        appendCard(html, "Passed Requests", String.valueOf(analysis.totalPassed), "pass");
        appendCard(html, "Failed Requests", String.valueOf(analysis.totalFailed),
                analysis.totalFailed > 0 ? "fail" : "pass");
        appendCard(html, "Pass %", String.format(Locale.ROOT, "%.1f%%", analysis.passPercent()), "pass");
        appendCard(html, "Failure %", String.format(Locale.ROOT, "%.1f%%", analysis.failPercent()),
                analysis.failPercent() > 0 ? "fail" : "pass");
        appendCard(html, "Users / Ramp-up / Duration",
                profile.users() + " / " + profile.rampUpSeconds() + "s / " + profile.durationSeconds() + "s", null);
        appendCard(html, "Test Duration", String.format(Locale.ROOT, "%.1fs", durationMs / 1000.0), null);
        appendCard(html, "Start Time", formatTimestamp(analysis.startTimestampMs), null);
        appendCard(html, "End Time", formatTimestamp(analysis.endTimestampMs), null);
        html.append("</div>");

        html.append("<h2>JMeter Plan Validation</h2>");
        html.append("<p class=\"").append(jmxValidation.isValid() ? "jmx-ok" : "jmx-bad").append("\">")
                .append(jmxValidation.isValid() ? "VALID - " : "INVALID - ")
                .append(escapeHtml(jmxFile == null ? "" : jmxFile.toString())).append("</p>");
        html.append("<ul>");
        html.append("<li>File exists: ").append(jmxValidation.fileExists).append("</li>");
        html.append("<li>Well-formed XML: ").append(jmxValidation.wellFormedXml).append("</li>");
        html.append("<li>Has TestPlan: ").append(jmxValidation.hasTestPlan).append("</li>");
        html.append("<li>Has ThreadGroup: ").append(jmxValidation.hasThreadGroup).append("</li>");
        html.append("<li>HTTP samplers: ").append(jmxValidation.httpSamplerCount).append("</li>");
        html.append("<li>Header Manager present: ").append(jmxValidation.hasHeaderManager).append("</li>");
        html.append("<li>Response assertions: ").append(jmxValidation.responseAssertionCount).append("</li>");
        html.append("</ul>");
        if (!jmxValidation.problems.isEmpty()) {
            html.append("<p class=\"jmx-bad\">Problems found:</p><ul>");
            for (String problem : jmxValidation.problems) {
                html.append("<li>").append(escapeHtml(problem)).append("</li>");
            }
            html.append("</ul>");
        }

        html.append("<h2>API-Level Results</h2><table><tr>")
                .append("<th>API</th><th>Method</th><th>Requests</th><th>Passed</th><th>Failed</th>")
                .append("<th>Pass %</th><th>Fail %</th><th>Avg (ms)</th><th>Min (ms)</th><th>Max (ms)</th>")
                .append("<th>Throughput (req/s)</th><th>Expected Status</th><th>Status Codes Seen</th></tr>");
        for (ApiResult api : analysis.distinctApiResults()) {
            boolean rowFailed = api.failed > 0;
            html.append("<tr class=\"").append(rowFailed ? "fail-row" : "pass-row").append("\">");
            html.append("<td>").append(escapeHtml(api.endpoint)).append("</td>");
            html.append("<td>").append(escapeHtml(api.method)).append("</td>");
            html.append("<td>").append(api.requests).append("</td>");
            html.append("<td>").append(api.passed).append("</td>");
            html.append("<td>").append(api.failed).append("</td>");
            html.append("<td>").append(String.format(Locale.ROOT, "%.1f%%", api.passPercent())).append("</td>");
            html.append("<td>").append(String.format(Locale.ROOT, "%.1f%%", api.failPercent())).append("</td>");
            html.append("<td>").append(String.format(Locale.ROOT, "%.0f", api.avgMs())).append("</td>");
            html.append("<td>").append(String.format(Locale.ROOT, "%.0f", api.minMs)).append("</td>");
            html.append("<td>").append(String.format(Locale.ROOT, "%.0f", api.maxMs)).append("</td>");
            html.append("<td>").append(String.format(Locale.ROOT, "%.2f", api.throughputPerSecond())).append("</td>");
            html.append("<td>").append(api.expectedStatusKnown ? String.valueOf(api.expectedStatus) : "unknown").append("</td>");
            html.append("<td>").append(escapeHtml(statusCodesText(api))).append("</td>");
            html.append("</tr>");
        }
        html.append("</table>");

        html.append("<h2>CRUD Lifecycle Results</h2>");
        if (analysis.crudLifecycles.isEmpty()) {
            html.append("<p>No CREATE-&gt;UPDATE-&gt;DELETE lifecycles were discovered in the captured "
                    + "inventory (no POST base path had a matching PUT/PATCH/DELETE to a same-resource "
                    + "{id} route).</p>");
        } else {
            for (CrudLifecycleResult lifecycle : analysis.crudLifecycles) {
                html.append("<div class=\"card\" style=\"width:100%;box-sizing:border-box;margin-bottom:10px\">");
                html.append("<div class=\"label\">Resource: ").append(escapeHtml(lifecycle.resourceName))
                        .append(" &nbsp; (id captured into ").append(escapeHtml(lifecycle.capturedId)).append(")</div>");
                html.append("<table><tr><th>Step</th><th>Method</th><th>Endpoint</th><th>Result</th></tr>");
                appendCrudStepRow(html, "Create", lifecycle.create);
                appendCrudStepRow(html, "Update", lifecycle.update);
                appendCrudStepRow(html, "Delete", lifecycle.delete);
                html.append("</table></div>");
            }
        }

        html.append("<h2>Skipped APIs</h2>");
        if (analysis.skipped.isEmpty()) {
            html.append("<p class=\"pass\">Skipped APIs = 0 - every included API produced at least one real sample.</p>");
        } else {
            html.append("<table><tr><th>API</th><th>Method</th><th>Reason</th></tr>");
            for (SkippedApi skipped : analysis.skipped) {
                html.append("<tr class=\"fail-row\"><td>").append(escapeHtml(skipped.endpoint)).append("</td><td>")
                        .append(escapeHtml(skipped.method)).append("</td><td>")
                        .append(escapeHtml(skipped.reason)).append("</td></tr>");
            }
            html.append("</table>");
        }

        html.append("<h2>Failure Details</h2>");
        boolean anyFailures = analysis.distinctApiResults().stream().anyMatch(a -> !a.failures.isEmpty());
        if (!anyFailures) {
            html.append("<p class=\"pass\">No failures recorded.</p>");
        } else {
            html.append("<table><tr><th>API</th><th>Method</th><th>Expected Status</th>")
                    .append("<th>Actual Status</th><th>Response Time (ms)</th><th>Timestamp</th>")
                    .append("<th>Error/Failure Reason</th></tr>");
            for (ApiResult api : analysis.distinctApiResults()) {
                for (SampleResult failure : api.failures) {
                    html.append("<tr class=\"fail-row\">");
                    html.append("<td>").append(escapeHtml(failure.endpoint)).append("</td>");
                    html.append("<td>").append(escapeHtml(failure.method)).append("</td>");
                    html.append("<td>").append(failure.expectedStatusKnown ? String.valueOf(failure.expectedStatus) : "unknown").append("</td>");
                    html.append("<td>").append(failure.actualStatus).append("</td>");
                    html.append("<td>").append(String.format(Locale.ROOT, "%.0f", failure.elapsedMs)).append("</td>");
                    html.append("<td>").append(formatTimestamp(failure.timestampMs)).append("</td>");
                    html.append("<td>").append(escapeHtml(failure.failureMessage)).append("</td>");
                    html.append("</tr>");
                }
            }
            html.append("</table>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static void appendCrudStepRow(StringBuilder html, String stepName, CrudStepResult step) {
        String status = step == null ? "NOT CAPTURED" : step.status;
        boolean isPass = "PASS".equals(status);
        boolean isBad = status != null && (status.startsWith("FAIL") || status.equals("NOT EXECUTED"));
        html.append("<tr class=\"").append(isBad ? "fail-row" : isPass ? "pass-row" : "").append("\">");
        html.append("<td>").append(escapeHtml(stepName)).append("</td>");
        html.append("<td>").append(escapeHtml(step == null ? "" : String.valueOf(step.method))).append("</td>");
        html.append("<td>").append(escapeHtml(step == null ? "" : String.valueOf(step.endpoint))).append("</td>");
        html.append("<td><span class=\"badge ").append(isPass ? "badge-pass" : isBad ? "badge-fail" : "")
                .append("\">").append(escapeHtml(status)).append("</span>");
        if (step != null && step.reason != null) {
            html.append(" - ").append(escapeHtml(step.reason));
        }
        html.append("</td></tr>");
    }

    private static void appendCard(StringBuilder html, String label, String value, String colorClass) {
        html.append("<div class=\"card\"><div class=\"label\">").append(escapeHtml(label)).append("</div>");
        html.append("<div class=\"value").append(colorClass == null ? "" : " " + colorClass).append("\">")
                .append(escapeHtml(value)).append("</div></div>");
    }

    private static String statusCodesText(ApiResult api) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : api.statusCodeCounts.entrySet()) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return text.toString();
    }

    private static String formatTimestamp(long epochMs) {
        if (epochMs <= 0) {
            return "N/A";
        }
        return TIMESTAMP.format(Instant.ofEpochMilli(epochMs));
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
