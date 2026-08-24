package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.inventory.ApiComparisonService.ComparisonResult;
import com.vigilx.apisecurity.inventory.ApiDefinition;

/**
 * Writes the API inventory comparison to its own, completely separate report tree - never anywhere
 * near {@code target/soak-test/**}, so the existing SOAK reports are untouched by this module.
 *
 * <p>Produces three files, matching Steps 4 and 14 of the API security/performance automation brief:
 * a structured JSON comparison, a human-readable text summary, and a missing-API traceability report
 * (Method | Endpoint | ApiMonitor? | HAR? | Duplicate? | In MissingApiRegistry? | Executed?) proving
 * no API was accidentally tested twice or silently skipped.
 */
public final class ApiComparisonReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiComparisonReportGenerator() {
    }

    public static void writeAll(Path reportsDirectory, ComparisonResult result,
                                List<ApiDefinition> registeredMissingApis) throws IOException {
        Files.createDirectories(reportsDirectory);
        writeJson(reportsDirectory.resolve("api-inventory-comparison.json"), result);
        writeText(reportsDirectory.resolve("api-inventory-comparison.txt"), result);
        writeMissingApiReport(reportsDirectory.resolve("missing-api-report.csv"), result, registeredMissingApis);
    }

    private static void writeJson(Path file, ComparisonResult result) throws IOException {
        var root = JSON.createObjectNode();
        root.put("monitorApiCount", result.monitorApis().size());
        root.put("harApiCount", result.harApis().size());
        root.put("duplicateCount", result.duplicates().size());
        root.put("missingFromMonitorCount", result.missingFromMonitor().size());
        root.put("combinedUniqueCount", result.combinedUnique().size());
        root.putPOJO("monitorApis", toJsonList(result.monitorApis()));
        root.putPOJO("harApis", toJsonList(result.harApis()));
        root.putPOJO("duplicates", toJsonList(result.duplicates()));
        root.putPOJO("missingFromMonitor", toJsonList(result.missingFromMonitor()));
        root.putPOJO("combinedUnique", toJsonList(result.combinedUnique()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        System.out.println("[API COMPARISON REPORT] JSON written to " + file);
    }

    private static List<Object> toJsonList(List<ApiDefinition> definitions) {
        List<Object> list = new ArrayList<>();
        for (ApiDefinition definition : definitions) {
            list.add(definition.toJsonMap());
        }
        return list;
    }

    private static void writeText(Path file, ComparisonResult result) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append(result.renderSummary()).append('\n');

        appendSection(text, "MISSING FROM APIMONITOR (candidates for MissingApiRegistry)", result.missingFromMonitor());
        appendSection(text, "DUPLICATES (already captured by ApiMonitor - not re-added)", result.duplicates());

        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        System.out.println("[API COMPARISON REPORT] Text summary written to " + file);
    }

    private static void appendSection(StringBuilder text, String title, List<ApiDefinition> definitions) {
        text.append(title).append(" (").append(definitions.size()).append(")\n");
        text.append("------------------------------------------------------------\n");
        if (definitions.isEmpty()) {
            text.append("(none)\n");
        }
        for (ApiDefinition definition : definitions) {
            text.append(String.format("%-7s %-70s host=%s%n", definition.method(),
                    definition.normalizedPath(), definition.host()));
        }
        text.append('\n');
    }

    /**
     * Method | Endpoint | ApiMonitor | HAR | Duplicate | Missing List | Executed - one row per
     * distinct API in the combined inventory, so it is visible at a glance that nothing was tested
     * twice and nothing was silently dropped.
     *
     * <p>The "Executed" column is honestly reported as {@code NO} for every row until the REST
     * Assured / security / JMeter execution phases exist - this report must never claim an
     * execution that has not actually happened.
     */
    private static void writeMissingApiReport(Path file, ComparisonResult result,
                                              List<ApiDefinition> registeredMissingApis) throws IOException {
        Set<String> monitorKeys = keysOf(result.monitorApis());
        Set<String> harKeys = keysOf(result.harApis());
        Set<String> duplicateKeys = keysOf(result.duplicates());
        Set<String> registeredKeys = keysOf(registeredMissingApis);

        StringBuilder csv = new StringBuilder();
        csv.append("Method,Endpoint,Host,ApiMonitor,HAR,Duplicate,InMissingApiRegistry,Executed\n");
        for (ApiDefinition definition : result.combinedUnique()) {
            String key = definition.comparisonKey();
            csv.append(csvField(definition.method())).append(',')
                    .append(csvField(definition.normalizedPath())).append(',')
                    .append(csvField(definition.host())).append(',')
                    .append(yesNo(monitorKeys.contains(key))).append(',')
                    .append(yesNo(harKeys.contains(key))).append(',')
                    .append(yesNo(duplicateKeys.contains(key))).append(',')
                    .append(yesNo(registeredKeys.contains(key))).append(',')
                    .append("NO (execution phase not yet implemented)")
                    .append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("[API COMPARISON REPORT] Missing-API traceability report written to " + file);
    }

    private static Set<String> keysOf(List<ApiDefinition> definitions) {
        Set<String> keys = new HashSet<>();
        for (ApiDefinition definition : definitions) {
            keys.add(definition.comparisonKey());
        }
        return keys;
    }

    private static String yesNo(boolean value) {
        return value ? "YES" : "NO";
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
