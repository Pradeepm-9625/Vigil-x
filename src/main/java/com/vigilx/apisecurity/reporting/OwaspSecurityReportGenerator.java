package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.security.OwaspCategory;
import com.vigilx.apisecurity.security.OwaspSecurityTestRunner.RunResult;
import com.vigilx.apisecurity.security.SecurityFinding;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;

/**
 * Writes the OWASP API Security Top 10 findings to their own report tree, separate from the API
 * inventory/functional reports and completely separate from {@code target/soak-test/**}.
 *
 * <p>The per-category status line is deliberately never just PASS/FAIL: it is PASS, FAIL, or
 * NOT_ASSESSED with the count of each verdict, so a category that was not meaningfully testable is
 * never confused with one that passed.
 */
public final class OwaspSecurityReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OwaspSecurityReportGenerator() {
    }

    public static void writeAll(Path reportsDirectory, RunResult result) throws IOException {
        Files.createDirectories(reportsDirectory);
        writeJson(reportsDirectory.resolve("owasp-security-report.json"), result);
        writeText(reportsDirectory.resolve("owasp-security-report.txt"), result);
        writeCsv(reportsDirectory.resolve("owasp-security-report.csv"), result);
    }

    private static void writeJson(Path file, RunResult result) throws IOException {
        var root = JSON.createObjectNode();
        root.put("authFailed", result.authFailed);
        root.put("authFailureReason", result.authFailureReason == null ? "" : result.authFailureReason);

        var categories = JSON.createObjectNode();
        for (Map.Entry<OwaspCategory, List<SecurityFinding>> entry : byCategory(result).entrySet()) {
            var categoryNode = JSON.createObjectNode();
            categoryNode.put("title", entry.getKey().title());
            categoryNode.put("status", categoryStatus(entry.getValue()));
            categoryNode.put("pass", count(entry.getValue(), Verdict.PASS));
            categoryNode.put("fail", count(entry.getValue(), Verdict.FAIL));
            categoryNode.put("notAssessed", count(entry.getValue(), Verdict.NOT_ASSESSED));
            var findings = JSON.createArrayNode();
            for (SecurityFinding finding : entry.getValue()) {
                findings.addPOJO(finding.toJsonMap());
            }
            categoryNode.putPOJO("findings", findings);
            categories.set(entry.getKey().id(), categoryNode);
        }
        root.putPOJO("categories", categories);

        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        System.out.println("[OWASP SECURITY REPORT] JSON written to " + file);
    }

    private static void writeText(Path file, RunResult result) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("============================================================\n");
        text.append("OWASP API SECURITY TOP 10 - RESULTS\n");
        text.append("============================================================\n\n");

        if (result.authFailed) {
            text.append("AUTHENTICATION FAILED - no checks were executed.\n");
            text.append("Reason: ").append(result.authFailureReason).append("\n");
        } else {
            for (Map.Entry<OwaspCategory, List<SecurityFinding>> entry : byCategory(result).entrySet()) {
                OwaspCategory category = entry.getKey();
                List<SecurityFinding> findings = entry.getValue();
                text.append(String.format("%-10s %-45s %s%n", category.id(), category.title(),
                        categoryStatus(findings)));
                for (SecurityFinding finding : findings) {
                    Map<String, Object> map = finding.toJsonMap();
                    text.append(String.format("    [%s] %s%n", map.get("verdict"), map.get("check")));
                    text.append("        ").append(map.get("summary")).append('\n');
                }
                text.append('\n');
            }
        }

        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        System.out.println("[OWASP SECURITY REPORT] Text summary written to " + file);
    }

    private static void writeCsv(Path file, RunResult result) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("OwaspCategory,Title,Check,Method,Endpoint,Verdict,Severity,Summary\n");
        for (SecurityFinding finding : result.findings) {
            Map<String, Object> map = finding.toJsonMap();
            csv.append(csvField(String.valueOf(map.get("owaspCategory")))).append(',')
                    .append(csvField(String.valueOf(map.get("owaspTitle")))).append(',')
                    .append(csvField(String.valueOf(map.get("check")))).append(',')
                    .append(csvField(String.valueOf(map.get("method")))).append(',')
                    .append(csvField(String.valueOf(map.get("endpoint")))).append(',')
                    .append(csvField(String.valueOf(map.get("verdict")))).append(',')
                    .append(csvField(String.valueOf(map.get("severity")))).append(',')
                    .append(csvField(String.valueOf(map.get("summary"))))
                    .append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("[OWASP SECURITY REPORT] CSV written to " + file);
    }

    private static Map<OwaspCategory, List<SecurityFinding>> byCategory(RunResult result) {
        Map<OwaspCategory, List<SecurityFinding>> grouped = new EnumMap<>(OwaspCategory.class);
        for (OwaspCategory category : OwaspCategory.values()) {
            grouped.put(category, new java.util.ArrayList<>());
        }
        for (SecurityFinding finding : result.findings) {
            grouped.get(finding.category()).add(finding);
        }
        return grouped;
    }

    private static String categoryStatus(List<SecurityFinding> findings) {
        if (findings.isEmpty()) {
            return "NOT_ASSESSED";
        }
        if (findings.stream().anyMatch(f -> f.verdict() == Verdict.FAIL)) {
            return "FAIL";
        }
        if (findings.stream().anyMatch(f -> f.verdict() == Verdict.PASS)) {
            return "PASS";
        }
        return "NOT_ASSESSED";
    }

    private static int count(List<SecurityFinding> findings, Verdict verdict) {
        return (int) findings.stream().filter(f -> f.verdict() == verdict).count();
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
