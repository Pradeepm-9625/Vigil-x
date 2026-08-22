package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.execution.ApiTestResult;
import com.vigilx.apisecurity.execution.ApiTestResult.Outcome;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.restassured.RestApiTestRunner.RunOutcome;

/**
 * Writes the REST Assured functional validation results to their own report tree, separate from
 * the API-inventory-comparison reports and completely separate from {@code target/soak-test/**}.
 */
public final class RestApiFunctionalReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RestApiFunctionalReportGenerator() {
    }

    public static void writeAll(Path reportsDirectory, RunOutcome outcome) throws IOException {
        Files.createDirectories(reportsDirectory);
        writeJson(reportsDirectory.resolve("api-functional-report.json"), outcome);
        writeText(reportsDirectory.resolve("api-functional-report.txt"), outcome);
        writeCsv(reportsDirectory.resolve("api-functional-report.csv"), outcome);
    }

    private static void writeJson(Path file, RunOutcome outcome) throws IOException {
        var root = JSON.createObjectNode();
        root.put("authFailed", outcome.authFailed);
        root.put("authFailureReason", outcome.authFailureReason == null ? "" : outcome.authFailureReason);
        root.put("executedCount", outcome.results.size());
        root.put("notEligibleCount", outcome.notEligible.size());
        root.put("passCount", count(outcome, Outcome.PASS));
        root.put("failCount", count(outcome, Outcome.FAIL));
        root.put("skippedCount", count(outcome, Outcome.SKIPPED));

        var executed = JSON.createArrayNode();
        for (ApiTestResult result : outcome.results) {
            executed.addPOJO(result.toJsonMap());
        }
        root.putPOJO("executed", executed);

        var notEligible = JSON.createArrayNode();
        for (Map.Entry<ApiDefinition, String> entry : outcome.notEligible.entrySet()) {
            var node = JSON.createObjectNode();
            node.putPOJO("api", entry.getKey().toJsonMap());
            node.put("reason", entry.getValue());
            notEligible.add(node);
        }
        root.putPOJO("notEligible", notEligible);

        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        System.out.println("[REST API FUNCTIONAL REPORT] JSON written to " + file);
    }

    private static void writeText(Path file, RunOutcome outcome) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("============================================================\n");
        text.append("API FUNCTIONAL VALIDATION (REST Assured)\n");
        text.append("============================================================\n\n");

        if (outcome.authFailed) {
            text.append("AUTHENTICATION FAILED - no APIs were executed.\n");
            text.append("Reason: ").append(outcome.authFailureReason).append("\n\n");
        } else {
            text.append(String.format("%-20s: %d%n", "Executed", outcome.results.size()));
            text.append(String.format("%-20s: %d%n", "Passed", count(outcome, Outcome.PASS)));
            text.append(String.format("%-20s: %d%n", "Failed", count(outcome, Outcome.FAIL)));
            text.append(String.format("%-20s: %d%n", "Skipped", count(outcome, Outcome.SKIPPED)));
            text.append(String.format("%-20s: %d%n", "Not auto-executed", outcome.notEligible.size()));
            text.append("\n");

            text.append("FAILURES\n------------------------------------------------------------\n");
            boolean anyFailure = false;
            for (ApiTestResult result : outcome.results) {
                if (result.outcome() == Outcome.FAIL) {
                    anyFailure = true;
                    text.append(String.format("%-7s %-60s expected=%-4d actual=%-4d %dms - %s%n",
                            result.method(), result.path(), result.expectedStatus(), result.actualStatus(),
                            result.responseTimeMs(), "(see JSON report for the note)"));
                }
            }
            if (!anyFailure) {
                text.append("(none)\n");
            }
        }

        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        System.out.println("[REST API FUNCTIONAL REPORT] Text summary written to " + file);
    }

    private static void writeCsv(Path file, RunOutcome outcome) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("Method,Endpoint,Host,ExpectedStatus,ActualStatus,ResponseTimeMs,Outcome,Note\n");
        for (ApiTestResult result : outcome.results) {
            Map<String, Object> map = result.toJsonMap();
            csv.append(csvField(String.valueOf(map.get("method")))).append(',')
                    .append(csvField(String.valueOf(map.get("path")))).append(',')
                    .append(csvField(String.valueOf(map.get("host")))).append(',')
                    .append(map.get("expectedStatus")).append(',')
                    .append(map.get("actualStatus")).append(',')
                    .append(map.get("responseTimeMs")).append(',')
                    .append(csvField(String.valueOf(map.get("outcome")))).append(',')
                    .append(csvField(String.valueOf(map.get("note"))))
                    .append('\n');
        }
        for (Map.Entry<ApiDefinition, String> entry : outcome.notEligible.entrySet()) {
            ApiDefinition definition = entry.getKey();
            csv.append(csvField(definition.method())).append(',')
                    .append(csvField(definition.normalizedPath())).append(',')
                    .append(csvField(definition.host())).append(',')
                    .append(",,,")
                    .append("NOT_EXECUTED,")
                    .append(csvField(entry.getValue()))
                    .append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("[REST API FUNCTIONAL REPORT] CSV written to " + file);
    }

    private static int count(RunOutcome outcome, Outcome target) {
        int count = 0;
        for (ApiTestResult result : outcome.results) {
            if (result.outcome() == target) {
                count++;
            }
        }
        return count;
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
