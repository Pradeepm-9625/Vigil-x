package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.zap.ZapScanRunner.RunResult;
import com.vigilx.utils.SecretMasker;

/**
 * Writes ZAP scan results to their own report tree - separate from every other report this module
 * produces and completely separate from {@code target/soak-test/**}. Every string field from ZAP's
 * own alert data is passed through {@link SecretMasker} before being written, in case a crafted URL
 * or evidence snippet ever echoes back the injected Bearer token.
 */
public final class ZapReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ZapReportGenerator() {
    }

    public static void writeAll(Path reportsDirectory, RunResult result) throws IOException {
        Files.createDirectories(reportsDirectory);
        writeJson(reportsDirectory.resolve("zap-report.json"), result);
        writeText(reportsDirectory.resolve("zap-report.txt"), result);
        writeCsv(reportsDirectory.resolve("zap-report.csv"), result);
    }

    private static void writeJson(Path file, RunResult result) throws IOException {
        var root = JSON.createObjectNode();
        root.put("daemonAvailable", result.daemonAvailable);
        root.put("authFailed", result.authFailed);
        root.put("authFailureReason", result.authFailureReason == null ? "" : result.authFailureReason);
        root.put("urlsFedToPassiveScan", result.urlsFedToPassiveScan);
        root.put("activeScanEnabled", result.activeScanEnabled);
        root.put("urlsActivelyScanned", result.urlsActivelyScanned);
        root.put("alertCount", result.alerts.size());

        var alerts = JSON.createArrayNode();
        for (Map<String, Object> alert : result.alerts) {
            alerts.addPOJO(maskAlert(alert));
        }
        root.putPOJO("alerts", alerts);

        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        System.out.println("[ZAP REPORT] JSON written to " + file);
    }

    private static void writeText(Path file, RunResult result) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("============================================================\n");
        text.append("OWASP ZAP SCAN RESULTS\n");
        text.append("============================================================\n\n");

        if (!result.daemonAvailable) {
            text.append("ZAP DAEMON NOT AVAILABLE - no scan was performed.\n");
            text.append("Check zap.home / zap.address / zap.port, or start ZAP manually and re-run.\n");
        } else if (result.authFailed) {
            text.append("AUTHENTICATION FAILED - no URLs were scanned.\n");
            text.append("Reason: ").append(result.authFailureReason).append('\n');
        } else {
            text.append(String.format("%-24s: %d%n", "URLs passively scanned", result.urlsFedToPassiveScan));
            text.append(String.format("%-24s: %s%n", "Active scan", result.activeScanEnabled ? "ENABLED" : "disabled (default)"));
            if (result.activeScanEnabled) {
                text.append(String.format("%-24s: %d%n", "URLs actively scanned", result.urlsActivelyScanned));
            }
            text.append(String.format("%-24s: %d%n", "Alerts", result.alerts.size()));
            text.append('\n');

            for (Map<String, Object> alert : result.alerts) {
                Map<String, Object> masked = maskAlert(alert);
                text.append(String.format("[%s] %s%n", masked.get("risk"), masked.get("alert")));
                text.append("    URL   : ").append(masked.get("url")).append('\n');
                text.append("    Param : ").append(masked.get("param")).append('\n');
                text.append("    Desc  : ").append(truncate(String.valueOf(masked.get("description")), 200)).append('\n');
                text.append('\n');
            }
            if (result.alerts.isEmpty()) {
                text.append("(no alerts raised)\n");
            }
        }

        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        System.out.println("[ZAP REPORT] Text summary written to " + file);
    }

    private static void writeCsv(Path file, RunResult result) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("Risk,Confidence,Alert,Url,Param,CweId,Description\n");
        for (Map<String, Object> alert : result.alerts) {
            Map<String, Object> masked = maskAlert(alert);
            csv.append(csvField(String.valueOf(masked.get("risk")))).append(',')
                    .append(csvField(String.valueOf(masked.get("confidence")))).append(',')
                    .append(csvField(String.valueOf(masked.get("alert")))).append(',')
                    .append(csvField(String.valueOf(masked.get("url")))).append(',')
                    .append(csvField(String.valueOf(masked.get("param")))).append(',')
                    .append(csvField(String.valueOf(masked.get("cweid")))).append(',')
                    .append(csvField(String.valueOf(masked.get("description"))))
                    .append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("[ZAP REPORT] CSV written to " + file);
    }

    /** Runs every string value in a ZAP alert through SecretMasker, in case a token was ever echoed back. */
    private static Map<String, Object> maskAlert(Map<String, Object> alert) {
        Map<String, Object> masked = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : alert.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue) {
                masked.put(entry.getKey(), SecretMasker.maskBody(stringValue, 1000));
            } else {
                masked.put(entry.getKey(), value);
            }
        }
        return masked;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
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
}
