package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.performance.JtlResultParser.LabelSummary;
import com.vigilx.apisecurity.performance.JtlResultParser.Summary;
import com.vigilx.apisecurity.performance.LoadProfile;

/**
 * Writes the JMeter performance results to their own report tree, separate from every other report
 * this module produces and completely separate from {@code target/soak-test/**}.
 */
public final class JmeterReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JmeterReportGenerator() {
    }

    public static void writeAll(Path reportsDirectory, String scenarioName, LoadProfile profile,
                                Summary summary, Path htmlReportDir) throws IOException {
        Files.createDirectories(reportsDirectory);
        writeJson(reportsDirectory.resolve("jmeter-report.json"), scenarioName, profile, summary, htmlReportDir);
        writeText(reportsDirectory.resolve("jmeter-report.txt"), scenarioName, profile, summary, htmlReportDir);
        writeCsv(reportsDirectory.resolve("jmeter-report.csv"), summary);
    }

    private static void writeJson(Path file, String scenarioName, LoadProfile profile, Summary summary,
                                  Path htmlReportDir) throws IOException {
        var root = JSON.createObjectNode();
        root.put("scenario", scenarioName);
        root.put("users", profile.users());
        root.put("rampUpSeconds", profile.rampUpSeconds());
        root.put("durationSeconds", profile.durationSeconds());
        root.put("totalSamples", summary.totalSamples);
        root.put("errorCount", summary.errorCount);
        root.put("errorRatePercent", summary.errorRatePercent);
        root.put("minMs", summary.minMs);
        root.put("maxMs", summary.maxMs);
        root.put("avgMs", summary.avgMs);
        root.put("medianMs", summary.medianMs);
        root.put("p90Ms", summary.p90Ms);
        root.put("p95Ms", summary.p95Ms);
        root.put("p99Ms", summary.p99Ms);
        root.put("throughputPerSecond", summary.throughputPerSecond);
        root.put("htmlDashboard", htmlReportDir == null ? "" : htmlReportDir.resolve("index.html").toString());

        var byLabel = JSON.createObjectNode();
        for (Map.Entry<String, LabelSummary> entry : summary.byLabel.entrySet()) {
            var node = JSON.createObjectNode();
            node.put("samples", entry.getValue().samples);
            node.put("errors", entry.getValue().errors);
            node.put("avgMs", entry.getValue().avgMs);
            node.put("p95Ms", entry.getValue().p95Ms);
            byLabel.set(entry.getKey(), node);
        }
        root.putPOJO("byEndpoint", byLabel);

        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        System.out.println("[JMETER REPORT] JSON written to " + file);
    }

    private static void writeText(Path file, String scenarioName, LoadProfile profile, Summary summary,
                                  Path htmlReportDir) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("============================================================\n");
        text.append("JMETER PERFORMANCE RESULTS - ").append(scenarioName).append('\n');
        text.append("============================================================\n\n");
        text.append(String.format("%-22s: %d%n", "Users", profile.users()));
        text.append(String.format("%-22s: %ds%n", "Ramp-up", profile.rampUpSeconds()));
        text.append(String.format("%-22s: %ds%n", "Duration", profile.durationSeconds()));
        text.append('\n');
        text.append(String.format("%-22s: %d%n", "Total samples", summary.totalSamples));
        text.append(String.format("%-22s: %d (%.2f%%)%n", "Errors", summary.errorCount, summary.errorRatePercent));
        text.append(String.format("%-22s: %.0f%n", "Min response (ms)", summary.minMs));
        text.append(String.format("%-22s: %.0f%n", "Avg response (ms)", summary.avgMs));
        text.append(String.format("%-22s: %.0f%n", "Median response (ms)", summary.medianMs));
        text.append(String.format("%-22s: %.0f%n", "P90 response (ms)", summary.p90Ms));
        text.append(String.format("%-22s: %.0f%n", "P95 response (ms)", summary.p95Ms));
        text.append(String.format("%-22s: %.0f%n", "P99 response (ms)", summary.p99Ms));
        text.append(String.format("%-22s: %.0f%n", "Max response (ms)", summary.maxMs));
        text.append(String.format("%-22s: %.2f req/s%n", "Throughput", summary.throughputPerSecond));
        text.append('\n');
        if (htmlReportDir != null) {
            text.append("HTML dashboard: ").append(htmlReportDir.resolve("index.html")).append("\n\n");
        }

        text.append("BY ENDPOINT\n------------------------------------------------------------\n");
        for (Map.Entry<String, LabelSummary> entry : summary.byLabel.entrySet()) {
            LabelSummary label = entry.getValue();
            text.append(String.format("%-45s samples=%-5d errors=%-4d avg=%-6.0fms p95=%-6.0fms%n",
                    entry.getKey(), label.samples, label.errors, label.avgMs, label.p95Ms));
        }

        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        System.out.println("[JMETER REPORT] Text summary written to " + file);
    }

    private static void writeCsv(Path file, Summary summary) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("Endpoint,Samples,Errors,AvgMs,P95Ms\n");
        for (Map.Entry<String, LabelSummary> entry : summary.byLabel.entrySet()) {
            LabelSummary label = entry.getValue();
            csv.append(csvField(entry.getKey())).append(',')
                    .append(label.samples).append(',')
                    .append(label.errors).append(',')
                    .append(String.format("%.0f", label.avgMs)).append(',')
                    .append(String.format("%.0f", label.p95Ms))
                    .append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        System.out.println("[JMETER REPORT] CSV written to " + file);
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
