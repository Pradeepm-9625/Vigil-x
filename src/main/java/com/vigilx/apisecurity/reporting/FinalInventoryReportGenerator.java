package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.inventory.FinalApiRecord;

/**
 * Writes the FINAL UNIQUE API INVENTORY (brief Phase 7) - one row per {@link FinalApiRecord}, with
 * its ID, category, source (APIMONITOR/HAR_MISSING/BOTH) and per-test-type eligibility - to its own
 * report files, separate from the comparison summary report.
 */
public final class FinalInventoryReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FinalInventoryReportGenerator() {
    }

    public static void writeAll(Path reportsDirectory, List<FinalApiRecord> records) throws IOException {
        Files.createDirectories(reportsDirectory);
        writeJson(reportsDirectory.resolve("final-api-inventory.json"), records);
        writeCsv(reportsDirectory.resolve("final-api-inventory.csv"), records);
    }

    private static void writeJson(Path file, List<FinalApiRecord> records) throws IOException {
        var array = JSON.createArrayNode();
        for (FinalApiRecord record : records) {
            array.addPOJO(record.toJsonMap());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), array);
        System.out.println("[FINAL INVENTORY REPORT] " + records.size() + " record(s) written to " + file);
    }

    private static void writeCsv(Path file, List<FinalApiRecord> records) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("ApiId,Method,NormalizedEndpoint,SampleUrl,Source,ApiMonitorPresent,HarPresent,"
                + "AuthenticationRequired,Category,EligibleFunctional,EligibleSecurity,EligiblePerformance,"
                + "ExclusionReason\n");
        for (FinalApiRecord record : records) {
            Map<String, Object> map = record.toJsonMap();
            csv.append(csvField(String.valueOf(map.get("apiId")))).append(',')
                    .append(csvField(String.valueOf(map.get("method")))).append(',')
                    .append(csvField(String.valueOf(map.get("normalizedEndpoint")))).append(',')
                    .append(csvField(String.valueOf(map.get("sampleUrl")))).append(',')
                    .append(csvField(String.valueOf(map.get("source")))).append(',')
                    .append(csvField(String.valueOf(map.get("apiMonitorPresent")))).append(',')
                    .append(csvField(String.valueOf(map.get("harPresent")))).append(',')
                    .append(csvField(String.valueOf(map.get("authenticationRequired")))).append(',')
                    .append(csvField(String.valueOf(map.get("category")))).append(',')
                    .append(map.get("eligibleForFunctionalTesting")).append(',')
                    .append(map.get("eligibleForSecurityTesting")).append(',')
                    .append(map.get("eligibleForPerformanceTesting")).append(',')
                    .append(csvField(String.valueOf(map.get("exclusionReason"))))
                    .append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("[FINAL INVENTORY REPORT] CSV written to " + file);
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
