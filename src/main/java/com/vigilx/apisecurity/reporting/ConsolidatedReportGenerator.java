package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the final consolidated API security/performance report from every sub-report this module
 * already wrote to disk - it never re-runs anything, never re-derives numbers, and never touches
 * {@code target/soak-test/**}. Reads whatever subset of
 * {@code api-inventory-comparison.json}, {@code api-functional-report.json},
 * {@code owasp-security-report.json}, {@code zap-report.json} and {@code jmeter-report.json} is
 * present in the reports directory - a phase that was never run is shown as "not run", never
 * fabricated as zero findings.
 *
 * <p>Visually matches the existing SOAK consolidated report (same CSS custom properties) so the two
 * read as one family of reports, while staying in their own, completely separate directory tree.
 */
public final class ConsolidatedReportGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ConsolidatedReportGenerator() {
    }

    public static void generate(Path reportsDirectory) {
        try {
            Files.createDirectories(reportsDirectory);

            JsonNode comparison = readIfExists(reportsDirectory.resolve("api-inventory-comparison.json"));
            JsonNode functional = readIfExists(reportsDirectory.resolve("api-functional-report.json"));
            JsonNode owasp = readIfExists(reportsDirectory.resolve("owasp-security-report.json"));
            JsonNode zap = readIfExists(reportsDirectory.resolve("zap-report.json"));
            JsonNode jmeter = readIfExists(reportsDirectory.resolve("jmeter-report.json"));

            writeJson(reportsDirectory.resolve("final-security-report.json"), comparison, functional, owasp, zap, jmeter);
            writeText(reportsDirectory.resolve("final-security-report.txt"), comparison, functional, owasp, zap, jmeter);
            writeHtml(reportsDirectory.resolve("final-security-report.html"), comparison, functional, owasp, zap, jmeter);

            System.out.println("[FINAL REPORT] " + reportsDirectory.resolve("final-security-report.html").toAbsolutePath());
        } catch (Exception exception) {
            System.err.println("[FINAL REPORT] Could not generate the consolidated report: " + exception.getMessage());
        }
    }

    private static JsonNode readIfExists(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return JSON.readTree(file.toFile());
        } catch (IOException exception) {
            System.err.println("[FINAL REPORT] Could not read " + file + ": " + exception.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------

    private static void writeJson(Path file, JsonNode comparison, JsonNode functional, JsonNode owasp,
                                  JsonNode zap, JsonNode jmeter) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("generatedAt", LocalDateTime.now().format(TIMESTAMP));
        root.set("apiInventoryComparison", section(comparison));
        root.set("restAssuredFunctional", section(functional));
        root.set("owaspSecurity", section(owasp));
        root.set("zapScan", section(zap));
        root.set("jmeterPerformance", section(jmeter));
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        System.out.println("[FINAL REPORT] JSON written to " + file);
    }

    private static ObjectNode section(JsonNode data) {
        ObjectNode node = JSON.createObjectNode();
        node.put("ran", data != null);
        if (data != null) {
            node.setAll((ObjectNode) data);
        }
        return node;
    }

    // ---------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------

    private static void writeText(Path file, JsonNode comparison, JsonNode functional, JsonNode owasp,
                                  JsonNode zap, JsonNode jmeter) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("============================================================\n");
        text.append("VIGILX API SECURITY & PERFORMANCE - FINAL CONSOLIDATED REPORT\n");
        text.append("============================================================\n");
        text.append("Generated: ").append(LocalDateTime.now().format(TIMESTAMP)).append("\n\n");

        text.append("API INVENTORY (Steps 1-6)\n------------------------------------------------------------\n");
        if (comparison != null) {
            text.append(String.format("%-24s: %d%n", "ApiMonitor APIs", intOf(comparison, "monitorApiCount")));
            text.append(String.format("%-24s: %d%n", "HAR APIs (unique)", intOf(comparison, "harApiCount")));
            text.append(String.format("%-24s: %d%n", "Missing from ApiMonitor", intOf(comparison, "missingFromMonitorCount")));
            text.append(String.format("%-24s: %d%n", "Combined unique", intOf(comparison, "combinedUniqueCount")));
        } else {
            text.append("(not run)\n");
        }

        text.append("\nREST ASSURED FUNCTIONAL (Step 7)\n------------------------------------------------------------\n");
        if (functional != null) {
            text.append(String.format("%-24s: %d%n", "Executed", intOf(functional, "executedCount")));
            text.append(String.format("%-24s: %d%n", "Passed", intOf(functional, "passCount")));
            text.append(String.format("%-24s: %d%n", "Failed", intOf(functional, "failCount")));
            text.append(String.format("%-24s: %d%n", "Not auto-executed (writes)", intOf(functional, "notEligibleCount")));
        } else {
            text.append("(not run)\n");
        }

        text.append("\nOWASP API SECURITY TOP 10 (Steps 8-9)\n------------------------------------------------------------\n");
        if (owasp != null) {
            text.append(categoryStatusLines(owasp));
        } else {
            text.append("(not run)\n");
        }

        text.append("\nOWASP ZAP (Step 10)\n------------------------------------------------------------\n");
        if (zap != null) {
            text.append(String.format("%-24s: %d%n", "URLs passively scanned", intOf(zap, "urlsFedToPassiveScan")));
            text.append(String.format("%-24s: %s%n", "Active scan", boolOf(zap, "activeScanEnabled") ? "ENABLED" : "disabled"));
            text.append(String.format("%-24s: %d%n", "Alerts", intOf(zap, "alertCount")));
        } else {
            text.append("(not run)\n");
        }

        text.append("\nJMETER PERFORMANCE (Step 11)\n------------------------------------------------------------\n");
        if (jmeter != null) {
            text.append(String.format("%-24s: %d%n", "Total samples", intOf(jmeter, "totalSamples")));
            text.append(String.format("%-24s: %.0fms%n", "Avg response", doubleOf(jmeter, "avgMs")));
            text.append(String.format("%-24s: %.0fms%n", "P95 response", doubleOf(jmeter, "p95Ms")));
            text.append(String.format("%-24s: %.2f req/s%n", "Throughput", doubleOf(jmeter, "throughputPerSecond")));
            text.append("Note: JMeter's raw error count treats any HTTP >=400 as an error, even for\n"
                    + "endpoints whose own real captured sample expects a non-2xx status - cross-check\n"
                    + "jmeter-report.txt against api-inventory-comparison.json before treating a JMeter\n"
                    + "\"error\" as a real regression.\n");
        } else {
            text.append("(not run)\n");
        }

        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        System.out.println("[FINAL REPORT] Text summary written to " + file);
    }

    private static String categoryStatusLines(JsonNode owasp) {
        StringBuilder text = new StringBuilder();
        JsonNode categories = owasp.path("categories");
        categories.properties().forEach(entry ->text.append(String.format("%-10s %-45s %s%n",
                entry.getKey(), entry.getValue().path("title").asText(""), entry.getValue().path("status").asText(""))));
        return text.toString();
    }

    // ---------------------------------------------------------------------
    // HTML - same CSS custom properties as SoakConsolidatedReportGenerator, its own report family
    // ---------------------------------------------------------------------

    private static void writeHtml(Path file, JsonNode comparison, JsonNode functional, JsonNode owasp,
                                  JsonNode zap, JsonNode jmeter) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>VigilX API Security &amp; Performance Report</title><style>");
        html.append(":root{--bg:#f6f7f9;--card:#fff;--text:#1c1f23;--muted:#606a76;--line:#e3e6ea;");
        html.append("--pass:#1a7f4b;--fail:#c0392b;--warn:#b8860b}");
        html.append("@media(prefers-color-scheme:dark){:root{--bg:#15181c;--card:#1d2126;--text:#e8eaed;");
        html.append("--muted:#9aa4b0;--line:#2c3238;--pass:#4ade80;--fail:#f87171;--warn:#fbbf24}}");
        html.append("*{box-sizing:border-box}body{margin:0;padding:32px 20px;background:var(--bg);");
        html.append("color:var(--text);font:15px/1.55 -apple-system,Segoe UI,Roboto,sans-serif}");
        html.append(".wrap{max-width:1200px;margin:0 auto}");
        html.append("h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:32px 0 12px;");
        html.append("padding-bottom:8px;border-bottom:1px solid var(--line)}");
        html.append(".sub{color:var(--muted);margin:0 0 24px;font-size:13px}");
        html.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px}");
        html.append(".card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:14px}");
        html.append(".card .n{font-size:22px;font-weight:700}.card .l{color:var(--muted);font-size:12px;");
        html.append("text-transform:uppercase;letter-spacing:.4px}");
        html.append(".n.fail{color:var(--fail)}.n.pass{color:var(--pass)}.n.warn{color:var(--warn)}");
        html.append("table{width:100%;border-collapse:collapse;background:var(--card);");
        html.append("border:1px solid var(--line);border-radius:8px;overflow:hidden;font-size:13px}");
        html.append("th,td{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);vertical-align:top}");
        html.append("th{background:rgba(128,128,128,.08);font-size:12px;text-transform:uppercase;");
        html.append("letter-spacing:.4px;color:var(--muted)}");
        html.append(".badge{display:inline-block;padding:2px 8px;border-radius:4px;font-weight:700;");
        html.append("font-size:12px;color:#fff}.badge.pass{background:var(--pass)}");
        html.append(".badge.fail{background:var(--fail)}.badge.na{background:var(--muted)}");
        html.append(".note{color:var(--muted);font-size:12px;margin-top:8px}");
        html.append("</style></head><body><div class=\"wrap\">");

        html.append("<h1>VigilX API Security &amp; Performance Report</h1>");
        html.append("<p class=\"sub\">Generated ").append(LocalDateTime.now().format(TIMESTAMP))
                .append(" &middot; separate from, and never affecting, the existing SOAK suite</p>");

        appendInventorySection(html, comparison);
        appendFunctionalSection(html, functional);
        appendOwaspSection(html, owasp);
        appendZapSection(html, zap);
        appendJmeterSection(html, jmeter);

        html.append("</div></body></html>");
        Files.writeString(file, html.toString(), StandardCharsets.UTF_8);
        System.out.println("[FINAL REPORT] HTML written to " + file);
    }

    private static void appendInventorySection(StringBuilder html, JsonNode data) {
        html.append("<h2>API Inventory (Steps 1-6)</h2>");
        if (data == null) {
            appendNotRun(html);
            return;
        }
        html.append("<div class=\"grid\">");
        appendCard(html, "ApiMonitor APIs", intOf(data, "monitorApiCount"), "pass");
        appendCard(html, "HAR APIs (unique)", intOf(data, "harApiCount"), "");
        appendCard(html, "Missing from Monitor", intOf(data, "missingFromMonitorCount"),
                intOf(data, "missingFromMonitorCount") > 0 ? "warn" : "pass");
        appendCard(html, "Combined Unique", intOf(data, "combinedUniqueCount"), "");
        html.append("</div>");
        html.append("<p class=\"note\">Full detail: api-inventory-comparison.json/.txt, missing-api-report.csv</p>");
    }

    private static void appendFunctionalSection(StringBuilder html, JsonNode data) {
        html.append("<h2>REST Assured Functional Validation (Step 7)</h2>");
        if (data == null) {
            appendNotRun(html);
            return;
        }
        html.append("<div class=\"grid\">");
        appendCard(html, "Executed", intOf(data, "executedCount"), "");
        appendCard(html, "Passed", intOf(data, "passCount"), "pass");
        appendCard(html, "Failed", intOf(data, "failCount"), intOf(data, "failCount") > 0 ? "fail" : "pass");
        appendCard(html, "Not Auto-Executed", intOf(data, "notEligibleCount"), "");
        html.append("</div>");
        html.append("<p class=\"note\">GET-only by policy; write endpoints are listed, never invoked. "
                + "Full detail: api-functional-report.json/.txt/.csv</p>");
    }

    private static void appendOwaspSection(StringBuilder html, JsonNode data) {
        html.append("<h2>OWASP API Security Top 10 (Steps 8-9)</h2>");
        if (data == null) {
            appendNotRun(html);
            return;
        }
        html.append("<table><thead><tr><th>Category</th><th>Title</th><th>Status</th>"
                + "<th>Pass</th><th>Fail</th><th>Not Assessed</th></tr></thead><tbody>");
        data.path("categories").properties().forEach(entry ->{
            JsonNode category = entry.getValue();
            String status = category.path("status").asText("");
            String badgeClass = "FAIL".equals(status) ? "fail" : "PASS".equals(status) ? "pass" : "na";
            html.append("<tr><td>").append(entry.getKey()).append("</td><td>")
                    .append(category.path("title").asText("")).append("</td><td>")
                    .append("<span class=\"badge ").append(badgeClass).append("\">").append(status).append("</span>")
                    .append("</td><td>").append(category.path("pass").asInt(0)).append("</td><td>")
                    .append(category.path("fail").asInt(0)).append("</td><td>")
                    .append(category.path("notAssessed").asInt(0)).append("</td></tr>");
        });
        html.append("</tbody></table>");
        html.append("<p class=\"note\">NOT_ASSESSED is a deliberate, honest outcome - not a fabricated pass. "
                + "Full detail: owasp-security-report.json/.txt/.csv</p>");
    }

    private static void appendZapSection(StringBuilder html, JsonNode data) {
        html.append("<h2>OWASP ZAP (Step 10)</h2>");
        if (data == null) {
            appendNotRun(html);
            return;
        }
        html.append("<div class=\"grid\">");
        appendCard(html, "URLs Scanned", intOf(data, "urlsFedToPassiveScan"), "");
        appendCard(html, "Alerts", intOf(data, "alertCount"), intOf(data, "alertCount") > 0 ? "warn" : "pass");
        html.append("</div>");
        html.append("<p class=\"note\">Active scan: ").append(boolOf(data, "activeScanEnabled") ? "ENABLED" : "disabled (default)")
                .append(". Full detail: zap-report.json/.txt/.csv</p>");
    }

    private static void appendJmeterSection(StringBuilder html, JsonNode data) {
        html.append("<h2>JMeter Performance (Step 11)</h2>");
        if (data == null) {
            appendNotRun(html);
            return;
        }
        html.append("<div class=\"grid\">");
        appendCard(html, "Total Samples", intOf(data, "totalSamples"), "");
        appendCard(html, "Avg (ms)", (int) doubleOf(data, "avgMs"), "");
        appendCard(html, "P95 (ms)", (int) doubleOf(data, "p95Ms"), "");
        html.append("</div>");
        html.append("<p class=\"note\">JMeter's own error count treats any HTTP&nbsp;&ge;400 as an error, "
                + "even where that is the endpoint's own expected/documented status - cross-check against "
                + "api-inventory-comparison.json before treating a JMeter \"error\" as a regression. "
                + "Full detail: jmeter-report.json/.txt/.csv, and JMeter's own HTML dashboard.</p>");
    }

    private static void appendNotRun(StringBuilder html) {
        html.append("<p class=\"note\">Not run in this pass.</p>");
    }

    private static void appendCard(StringBuilder html, String label, int value, String cssClass) {
        html.append("<div class=\"card\"><div class=\"n").append(cssClass.isEmpty() ? "" : " " + cssClass)
                .append("\">").append(value).append("</div><div class=\"l\">").append(label).append("</div></div>");
    }

    private static int intOf(JsonNode node, String field) {
        return node.path(field).asInt(0);
    }

    private static double doubleOf(JsonNode node, String field) {
        return node.path(field).asDouble(0);
    }

    private static boolean boolOf(JsonNode node, String field) {
        return node.path(field).asBoolean(false);
    }
}
