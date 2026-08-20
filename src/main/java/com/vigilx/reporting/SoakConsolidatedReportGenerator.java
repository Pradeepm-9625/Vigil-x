package com.vigilx.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds the multi-run consolidated soak report from every completed run's already-written data.
 *
 * <p>Purely additive: this never runs a test, never touches an individual run's own report, and reads
 * everything through {@link SoakRunSummaryStore}. Call {@link #generate()} once after each run
 * finishes (see {@code SoakHealthCheckRunner}); it rescans every {@code run-<timestamp>} folder under
 * {@code target/soak-test} and rewrites the three files below from scratch, so it is safe to call as
 * often as needed and never accumulates duplicates - the same run folder just gets read again and
 * produces the same row.
 *
 * <p>Output, under {@code target/soak-test/consolidated-report/}:
 * <ul>
 *   <li>{@code soak-consolidated-report.html} - human-readable, linking back to every run's own report
 *   <li>{@code soak-summary.json} - the machine-readable source of the consolidated data
 *   <li>{@code soak-summary.csv} - one row per run, for Excel
 * </ul>
 *
 * <p>Every write goes to a temp file first and is then moved into place, so a reader (or a second,
 * overlapping Task Scheduler run) never observes a half-written file.
 */
public final class SoakConsolidatedReportGenerator {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIRECTORY_NAME = "consolidated-report";

    private SoakConsolidatedReportGenerator() {
    }

    /** Rescans every completed run and rewrites the consolidated HTML/JSON/CSV. Never throws. */
    public static void generate() {
        try {
            List<SoakRunSummary> runs = SoakRunSummaryStore.loadAll();
            Path outputDir = SoakRunContext.root().resolve(DIRECTORY_NAME);
            Files.createDirectories(outputDir);

            ExecutiveSummary summary = ExecutiveSummary.of(runs);
            List<PageStat> pageStats = pageStats(runs);
            List<ApiStat> apiStats = apiStats(runs);

            writeJson(outputDir, runs, summary, pageStats, apiStats);
            writeCsv(outputDir, runs);
            writeHtml(outputDir, runs, summary, pageStats, apiStats);

            System.out.println("[SOAK CONSOLIDATED] " + runs.size() + " run(s) -> "
                    + outputDir.resolve("soak-consolidated-report.html").toAbsolutePath());
        } catch (Exception exception) {
            System.err.println("[SOAK CONSOLIDATED] Report generation failed: " + exception.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Aggregation
    // ---------------------------------------------------------------------

    /** Executive-summary counters, each derived only from runs that actually reported that value. */
    private static final class ExecutiveSummary {
        int totalRuns;
        int passedRuns;
        int failedRuns;
        long totalApiRequests;
        long totalApiFailures;
        long totalApiTimeouts;
        String firstRunTime = "N/A";
        String lastRunTime = "N/A";

        static ExecutiveSummary of(List<SoakRunSummary> runs) {
            ExecutiveSummary summary = new ExecutiveSummary();
            summary.totalRuns = runs.size();
            for (SoakRunSummary run : runs) {
                if ("PASS".equalsIgnoreCase(run.overallResult)) {
                    summary.passedRuns++;
                } else {
                    // Anything not an explicit PASS - FAIL, or unknown/missing - counts as not passed.
                    // A missing value is never assumed to be a pass.
                    summary.failedRuns++;
                }
                if (run.apiTotalRequests != null) {
                    summary.totalApiRequests += run.apiTotalRequests;
                }
                if (run.apiFailed != null) {
                    summary.totalApiFailures += run.apiFailed;
                }
                if (run.apiTimeouts != null) {
                    summary.totalApiTimeouts += run.apiTimeouts;
                }
            }
            if (!runs.isEmpty()) {
                summary.firstRunTime = value(runs.get(0).startTime);
                summary.lastRunTime = value(runs.get(runs.size() - 1).startTime);
            }
            return summary;
        }

        double passPercentage() {
            return totalRuns == 0 ? 0.0 : (100.0 * passedRuns / totalRuns);
        }
    }

    /** One page/module's pass rate across every run that reported it. */
    private static final class PageStat {
        String page;
        int total;
        int passed;
        int failed;

        double passRate() {
            return total == 0 ? 0.0 : (100.0 * passed / total);
        }
    }

    private static List<PageStat> pageStats(List<SoakRunSummary> runs) {
        Map<String, PageStat> byPage = new LinkedHashMap<>();
        for (SoakRunSummary run : runs) {
            for (Map.Entry<String, String> entry : run.pageResults.entrySet()) {
                PageStat stat = byPage.computeIfAbsent(entry.getKey(), name -> {
                    PageStat created = new PageStat();
                    created.page = name;
                    return created;
                });
                stat.total++;
                if ("PASS".equalsIgnoreCase(entry.getValue())) {
                    stat.passed++;
                } else {
                    stat.failed++;
                }
            }
        }
        List<PageStat> stats = new ArrayList<>(byPage.values());
        stats.sort(Comparator.comparing((PageStat stat) -> stat.page));
        return stats;
    }

    /** One distinct (method + URL)'s failure history across every run. */
    private static final class ApiStat {
        String method;
        String url;
        int failureCount;
        TreeSet<String> statuses = new TreeSet<>();
        TreeSet<String> affectedRuns = new TreeSet<>();
    }

    private static List<ApiStat> apiStats(List<SoakRunSummary> runs) {
        Map<String, ApiStat> byEndpoint = new LinkedHashMap<>();
        for (SoakRunSummary run : runs) {
            String runLabel = run.runId == null ? run.runFolder : run.runId;
            for (SoakRunSummary.ApiFailureRecord record : run.apiFailureRecords) {
                String method = record.method == null ? "N/A" : record.method;
                String url = record.url == null ? "N/A" : record.url;
                String key = method + " " + url;
                ApiStat stat = byEndpoint.computeIfAbsent(key, ignored -> {
                    ApiStat created = new ApiStat();
                    created.method = method;
                    created.url = url;
                    return created;
                });
                stat.failureCount += record.occurrences;
                if (record.status != null) {
                    stat.statuses.add(record.status);
                }
                if (runLabel != null) {
                    stat.affectedRuns.add(runLabel);
                }
            }
        }
        List<ApiStat> stats = new ArrayList<>(byEndpoint.values());
        stats.sort(Comparator.comparingInt((ApiStat stat) -> stat.failureCount).reversed());
        return stats;
    }

    // ---------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------

    private static void writeJson(Path outputDir, List<SoakRunSummary> runs, ExecutiveSummary summary,
                                  List<PageStat> pageStats, List<ApiStat> apiStats) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", LocalDateTime.now().format(TIMESTAMP));
        root.put("totalRuns", summary.totalRuns);
        root.put("passedRuns", summary.passedRuns);
        root.put("failedRuns", summary.failedRuns);
        root.put("passPercentage", round(summary.passPercentage()));
        root.put("totalApiRequests", summary.totalApiRequests);
        root.put("totalApiFailures", summary.totalApiFailures);
        root.put("totalApiTimeouts", summary.totalApiTimeouts);
        root.put("firstRunTime", summary.firstRunTime);
        root.put("lastRunTime", summary.lastRunTime);

        List<Object> runRows = new ArrayList<>();
        int number = 1;
        for (SoakRunSummary run : runs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runNumber", number++);
            row.put("runId", run.runId);
            row.put("runIdIsFallback", run.runIdIsFallback);
            row.put("runFolder", run.runFolder);
            row.put("startTime", value(run.startTime));
            row.put("endTime", value(run.endTime));
            row.put("duration", value(run.duration));
            row.put("overallStatus", value(run.overallResult));
            row.put("pagesTotal", run.pagesTotal);
            row.put("pagesPassed", run.pagesPassed);
            row.put("pagesFailed", run.pagesFailed);
            row.put("failedPages", run.failedPages);
            row.put("apiRequests", run.apiTotalRequests);
            row.put("apiFailures", run.apiFailed);
            row.put("apiTimeouts", run.apiTimeouts);
            row.put("streamFailureCount", run.streamFailureCount);
            row.put("individualReportPath", run.individualReportPath);
            row.put("evidenceFolder", run.evidenceFolder);
            row.put("summaryUnavailable", run.summaryUnavailable);
            runRows.add(row);
        }
        root.put("runs", runRows);

        List<Object> pageRows = new ArrayList<>();
        for (PageStat stat : pageStats) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page", stat.page);
            row.put("totalRuns", stat.total);
            row.put("passed", stat.passed);
            row.put("failed", stat.failed);
            row.put("passRate", round(stat.passRate()));
            pageRows.add(row);
        }
        root.put("pageSummary", pageRows);

        List<Object> apiRows = new ArrayList<>();
        for (ApiStat stat : apiStats) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", stat.method);
            row.put("url", stat.url);
            row.put("failureCount", stat.failureCount);
            row.put("httpStatuses", new ArrayList<>(stat.statuses));
            row.put("affectedRuns", new ArrayList<>(stat.affectedRuns));
            apiRows.add(row);
        }
        root.put("apiHealthSummary", apiRows);

        List<Object> failureRows = new ArrayList<>();
        for (SoakRunSummary run : runs) {
            for (SoakRunSummary.ApiFailureRecord record : run.apiFailureRecords) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("runId", run.runId);
                row.put("timestamp", value(record.timestamp));
                row.put("page", value(record.page));
                row.put("method", value(record.method));
                row.put("url", value(record.url));
                row.put("status", record.status == null ? "N/A" : record.status);
                row.put("statusText", value(record.statusText));
                row.put("screenshot", record.screenshot);
                row.put("individualReportPath", run.individualReportPath);
                failureRows.add(row);
            }
        }
        root.put("failureAnalysis", failureRows);

        writeAtomically(outputDir.resolve("soak-summary.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    // ---------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------

    private static void writeCsv(Path outputDir, List<SoakRunSummary> runs) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", "Run", "RunID", "RunFolder", "StartTime", "EndTime", "Duration",
                "OverallStatus", "PagesTotal", "PagesPassed", "PagesFailed", "ApiRequests", "ApiFailures",
                "ApiTimeouts", "IndividualReportPath")).append("\r\n");

        int number = 1;
        for (SoakRunSummary run : runs) {
            csv.append(String.join(",",
                    csvCell(number++),
                    csvCell(run.runId),
                    csvCell(run.runFolder),
                    csvCell(value(run.startTime)),
                    csvCell(value(run.endTime)),
                    csvCell(value(run.duration)),
                    csvCell(value(run.overallResult)),
                    csvCell(run.pagesTotal),
                    csvCell(run.pagesPassed),
                    csvCell(run.pagesFailed),
                    csvCell(run.apiTotalRequests),
                    csvCell(run.apiFailed),
                    csvCell(run.apiTimeouts),
                    csvCell(run.individualReportPath)))
                    .append("\r\n");
        }
        writeAtomically(outputDir.resolve("soak-summary.csv"), csv.toString());
    }

    private static String csvCell(Object value) {
        String text = value == null ? "N/A" : String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    // ---------------------------------------------------------------------
    // HTML
    // ---------------------------------------------------------------------

    private static void writeHtml(Path outputDir, List<SoakRunSummary> runs, ExecutiveSummary summary,
                                  List<PageStat> pageStats, List<ApiStat> apiStats) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Soak Consolidated Report</title><style>")
                .append(":root{--bg:#f6f7f9;--card:#fff;--text:#1c1f23;--muted:#606a76;--line:#e3e6ea;")
                .append("--pass:#1a7f4b;--fail:#c0392b;--warn:#b8860b}")
                .append("@media(prefers-color-scheme:dark){:root{--bg:#15181c;--card:#1d2126;--text:#e8eaed;")
                .append("--muted:#9aa4b0;--line:#2c3238;--pass:#4ade80;--fail:#f87171;--warn:#fbbf24}}")
                .append("*{box-sizing:border-box}body{margin:0;padding:32px 20px;background:var(--bg);")
                .append("color:var(--text);font:15px/1.55 -apple-system,Segoe UI,Roboto,sans-serif}")
                .append(".wrap{max-width:1300px;margin:0 auto}")
                .append("h1{font-size:24px;margin:0 0 4px}h2{font-size:17px;margin:32px 0 12px;")
                .append("padding-bottom:8px;border-bottom:1px solid var(--line)}")
                .append(".sub{color:var(--muted);margin:0 0 24px;font-size:13px}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}")
                .append(".card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:14px}")
                .append(".card .n{font-size:22px;font-weight:700}.card .l{color:var(--muted);font-size:12px;")
                .append("text-transform:uppercase;letter-spacing:.4px}")
                .append(".n.fail{color:var(--fail)}.n.pass{color:var(--pass)}.n.warn{color:var(--warn)}")
                .append("table{width:100%;border-collapse:collapse;background:var(--card);")
                .append("border:1px solid var(--line);border-radius:8px;overflow:hidden;font-size:13px}")
                .append("th,td{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);")
                .append("vertical-align:top}th{background:rgba(128,128,128,.08);font-size:12px;")
                .append("text-transform:uppercase;letter-spacing:.4px;color:var(--muted)}")
                .append("tr:last-child td{border-bottom:none}code{font:12px ui-monospace,Menlo,Consolas,monospace;")
                .append("word-break:break-all}.tw{overflow-x:auto}")
                .append(".s{font-weight:700}.s.e{color:var(--fail)}.s.p{color:var(--pass)}")
                .append("a{color:inherit}.badge{display:inline-block;padding:2px 8px;border-radius:4px;")
                .append("font-weight:700;font-size:12px;color:#fff}.badge.pass{background:var(--pass)}")
                .append(".badge.fail{background:var(--fail)}.badge.na{background:var(--muted)}")
                .append("</style></head><body><div class=\"wrap\">");

        html.append("<h1>Soak Consolidated Report</h1>")
                .append("<p class=\"sub\">Generated ").append(escape(LocalDateTime.now().format(TIMESTAMP)))
                .append(" &middot; ").append(summary.totalRuns).append(" run(s) discovered under ")
                .append("<code>target/soak-test</code></p>");

        html.append("<h2>Executive summary</h2><div class=\"grid\">")
                .append(card("Total runs", String.valueOf(summary.totalRuns), ""))
                .append(card("Passed runs", String.valueOf(summary.passedRuns), "pass"))
                .append(card("Failed runs", String.valueOf(summary.failedRuns),
                        summary.failedRuns > 0 ? "fail" : "pass"))
                .append(card("Pass percentage", formatPercent(summary.passPercentage()),
                        summary.passPercentage() >= 100 ? "pass" : summary.failedRuns > 0 ? "warn" : "pass"))
                .append(card("Total API requests", String.valueOf(summary.totalApiRequests), ""))
                .append(card("Total API failures", String.valueOf(summary.totalApiFailures),
                        summary.totalApiFailures > 0 ? "fail" : "pass"))
                .append(card("Total API timeouts", String.valueOf(summary.totalApiTimeouts),
                        summary.totalApiTimeouts > 0 ? "warn" : "pass"))
                .append(card("First run", escape(summary.firstRunTime), ""))
                .append(card("Last run", escape(summary.lastRunTime), ""))
                .append("</div>");

        if (runs.isEmpty()) {
            html.append("<h2>Runs</h2><p>No completed soak runs found yet under ")
                    .append("<code>target/soak-test</code>. This page will populate automatically as runs complete.</p>");
        } else {
            html.append("<h2>Run summary</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Run</th><th>Run ID</th><th>Start</th><th>End</th><th>Duration</th>")
                    .append("<th>Status</th><th>Pages P/F</th><th>API Req</th><th>API Fail</th>")
                    .append("<th>API Timeout</th><th>Report</th></tr>");
            int number = 1;
            for (SoakRunSummary run : runs) {
                html.append("<tr><td>").append(number++).append("</td><td><code>")
                        .append(escape(value(run.runId))).append("</code>")
                        .append(run.runIdIsFallback ? " <span class=\"sub\">(folder name)</span>" : "")
                        .append("</td><td>").append(escape(value(run.startTime))).append("</td><td>")
                        .append(escape(value(run.endTime))).append("</td><td>")
                        .append(escape(value(run.duration))).append("</td><td>")
                        .append(statusBadge(run.overallResult)).append("</td><td>")
                        .append(numberOrNa(run.pagesPassed)).append(" / ").append(numberOrNa(run.pagesFailed))
                        .append("</td><td>").append(numberOrNa(run.apiTotalRequests)).append("</td><td>")
                        .append(numberOrNa(run.apiFailed)).append("</td><td>")
                        .append(numberOrNa(run.apiTimeouts)).append("</td><td>")
                        .append(reportLink(run)).append("</td></tr>");
            }
            html.append("</table></div>");
        }

        if (!pageStats.isEmpty()) {
            html.append("<h2>Page-level summary</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Page / module</th><th>Total runs</th><th>Passed</th><th>Failed</th>")
                    .append("<th>Pass rate</th></tr>");
            for (PageStat stat : pageStats) {
                html.append("<tr><td>").append(escape(stat.page)).append("</td><td>").append(stat.total)
                        .append("</td><td class=\"s p\">").append(stat.passed).append("</td><td class=\"s e\">")
                        .append(stat.failed).append("</td><td>").append(formatPercent(stat.passRate()))
                        .append("</td></tr>");
            }
            html.append("</table></div>");
        }

        if (!apiStats.isEmpty()) {
            html.append("<h2>API health summary</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Method</th><th>URL</th><th>Failure count</th><th>HTTP status</th>")
                    .append("<th>Affected runs</th></tr>");
            for (ApiStat stat : apiStats) {
                html.append("<tr><td>").append(escape(stat.method)).append("</td><td><code>")
                        .append(escape(stat.url)).append("</code></td><td class=\"s e\">")
                        .append(stat.failureCount).append("</td><td>")
                        .append(escape(String.join(", ", stat.statuses))).append("</td><td>")
                        .append(escape(String.join(", ", stat.affectedRuns))).append("</td></tr>");
            }
            html.append("</table></div>");
        }

        List<SoakRunSummary> withFailures = new ArrayList<>();
        int totalFailureRecords = 0;
        for (SoakRunSummary run : runs) {
            if (!run.apiFailureRecords.isEmpty()) {
                withFailures.add(run);
                totalFailureRecords += run.apiFailureRecords.size();
            }
        }
        if (totalFailureRecords > 0) {
            html.append("<h2>Failure analysis (").append(totalFailureRecords).append(")</h2><div class=\"tw\"><table>")
                    .append("<tr><th>Run ID</th><th>Timestamp</th><th>Page</th><th>Method</th><th>URL</th>")
                    .append("<th>Status</th><th>Screenshot</th><th>Report</th></tr>");
            for (SoakRunSummary run : withFailures) {
                for (SoakRunSummary.ApiFailureRecord record : run.apiFailureRecords) {
                    html.append("<tr><td><code>").append(escape(value(run.runId))).append("</code></td><td>")
                            .append(escape(value(record.timestamp))).append("</td><td>")
                            .append(escape(value(record.page))).append("</td><td>")
                            .append(escape(value(record.method))).append("</td><td><code>")
                            .append(escape(value(record.url))).append("</code></td><td class=\"s e\">")
                            .append(escape(record.status == null ? "N/A" : record.status)).append("</td><td>")
                            .append(screenshotLink(record.screenshot)).append("</td><td>")
                            .append(reportLink(run)).append("</td></tr>");
                }
            }
            html.append("</table></div>");
        }

        html.append("</div></body></html>");
        writeAtomically(outputDir.resolve("soak-consolidated-report.html"), html.toString());
    }

    private static String reportLink(SoakRunSummary run) {
        if (run.individualReportPath == null) {
            return "N/A";
        }
        return "<a href=\"" + escape(relativeFromConsolidated(run.individualReportPath))
                + "\">View report</a>";
    }

    private static String screenshotLink(String screenshot) {
        if (screenshot == null || screenshot.isBlank()) {
            return "N/A";
        }
        return "<a href=\"" + escape(relativeFromConsolidated(screenshot)) + "\">View</a>";
    }

    /**
     * {@code consolidated-report/} is a sibling of every {@code run-<timestamp>/} folder under
     * {@code target/soak-test/}, so a project-root-relative path is turned into a link by dropping the
     * shared {@code target/soak-test/} prefix and stepping back out one level.
     */
    private static String relativeFromConsolidated(String projectRootRelativePath) {
        String normalized = projectRootRelativePath.replace('\\', '/');
        String marker = "target/soak-test/";
        int index = normalized.indexOf(marker);
        if (index < 0) {
            return normalized;
        }
        return "../" + normalized.substring(index + marker.length());
    }

    private static String card(String label, String number, String tone) {
        return "<div class=\"card\"><div class=\"n " + tone + "\">" + escape(number)
                + "</div><div class=\"l\">" + escape(label) + "</div></div>";
    }

    private static String statusBadge(String status) {
        if (status == null || status.isBlank()) {
            return "<span class=\"badge na\">N/A</span>";
        }
        if ("PASS".equalsIgnoreCase(status)) {
            return "<span class=\"badge pass\">PASS</span>";
        }
        return "<span class=\"badge fail\">" + escape(status) + "</span>";
    }

    private static String numberOrNa(Integer value) {
        return value == null ? "N/A" : String.valueOf(value);
    }

    private static String formatPercent(double percent) {
        return String.format("%.1f%%", percent);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "N/A" : text;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Writes to a sibling temp file, then atomically (or as close as the platform allows) replaces. */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
