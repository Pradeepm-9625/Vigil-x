package com.vigilx.reporting;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Discovers every completed soak run under {@code target/soak-test} and parses it into a
 * {@link SoakRunSummary}, purely by reading files the run itself already wrote - no test execution,
 * no re-derivation of results.
 *
 * <p>"Completed" means {@code final-report/soak-test-summary.json} exists: that file is written at the
 * very end of {@link com.vigilx.reporting.SoakReporter#writeAll}, so its presence is exactly the signal
 * that the run finished (a run still executing, or one Windows Task Scheduler killed mid-flight, has no
 * such file yet and is correctly left out until it does). A summary file that exists but fails to parse
 * still produces a {@link SoakRunSummary} - marked {@link SoakRunSummary#summaryUnavailable} - so one
 * corrupt run can never hide the runs around it.
 *
 * <p>Every call re-scans the folder tree from scratch; nothing is cached or persisted between calls.
 * That is what makes this idempotent for free: the same {@code run-<timestamp>} folder is read at most
 * once per scan, so a run ID can never appear twice in one generation pass, and re-running the
 * generator (e.g. because Windows Task Scheduler fires again) just reproduces the same result plus
 * whatever new runs completed since.
 */
public final class SoakRunSummaryStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SoakRunSummaryStore() {
    }

    /** All completed runs under {@code target/soak-test}, oldest first. Never throws. */
    public static List<SoakRunSummary> loadAll() {
        return loadAll(SoakRunContext.root());
    }

    /** Same as {@link #loadAll()}, against an explicit root - kept for testability. */
    public static List<SoakRunSummary> loadAll(Path soakTestRoot) {
        List<SoakRunSummary> runs = new ArrayList<>();
        if (soakTestRoot == null || !Files.isDirectory(soakTestRoot)) {
            return runs;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(soakTestRoot, "run-*")) {
            for (Path runDir : stream) {
                try {
                    if (!Files.isDirectory(runDir)) {
                        continue;
                    }
                    SoakRunSummary summary = loadOne(runDir);
                    if (summary != null) {
                        runs.add(summary);
                    }
                } catch (Exception exception) {
                    // One bad folder must never take the rest of the scan down with it.
                    System.err.println("[SOAK CONSOLIDATED] Skipping " + runDir + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            System.err.println("[SOAK CONSOLIDATED] Could not list " + soakTestRoot + ": " + exception.getMessage());
        }

        runs.sort(Comparator
                .comparing((SoakRunSummary run) -> run.startTime == null ? "" : run.startTime)
                .thenComparing(run -> run.runFolder == null ? "" : run.runFolder));
        return runs;
    }

    /** One run folder, or {@code null} when it is not yet complete (no summary.json written). */
    private static SoakRunSummary loadOne(Path runDir) {
        Path summaryFile = runDir.resolve("final-report").resolve("soak-test-summary.json");
        if (!Files.exists(summaryFile)) {
            // Still executing, or the process never got far enough to report - not "completed" yet.
            return null;
        }

        SoakRunSummary run = new SoakRunSummary();
        run.runFolder = runDir.getFileName().toString();
        run.runId = run.runFolder;
        run.runIdIsFallback = true;
        run.evidenceFolder = SoakRunContext.relative(runDir.toString());
        run.individualReportPath = SoakRunContext.relative(
                runDir.resolve("final-report").resolve("soak-test-report.html").toString());

        try {
            JsonNode root = JSON.readTree(summaryFile.toFile());
            applySummary(run, root);
        } catch (Exception exception) {
            System.err.println("[SOAK CONSOLIDATED] Could not parse " + summaryFile + ": " + exception.getMessage());
            run.summaryUnavailable = true;
        }

        // Optional: structured per-API failure detail. Its absence does not affect completeness.
        Path apiFailuresFile = runDir.resolve("api-failures").resolve("api-failures.json");
        if (Files.exists(apiFailuresFile)) {
            try {
                JsonNode array = JSON.readTree(apiFailuresFile.toFile());
                applyApiFailures(run, array);
            } catch (Exception exception) {
                System.err.println("[SOAK CONSOLIDATED] Could not parse " + apiFailuresFile + ": "
                        + exception.getMessage());
            }
        }

        return run;
    }

    private static void applySummary(SoakRunSummary run, JsonNode root) {
        String executionId = text(root, "executionId");
        if (executionId != null && !executionId.isBlank()) {
            run.runId = executionId;
            run.runIdIsFallback = false;
        }

        run.overallResult = text(root, "overallResult");
        run.startTime = text(root, "startTime");
        run.endTime = text(root, "endTime");
        run.duration = text(root, "duration");

        JsonNode pages = root.path("pages");
        run.pagesTotal = intOrNull(pages, "total");
        run.pagesPassed = intOrNull(pages, "passed");
        run.pagesFailed = intOrNull(pages, "failed");
        for (JsonNode name : pages.path("failedPages")) {
            run.failedPages.add(name.asText());
        }
        JsonNode results = pages.path("results");
        if (results.isObject()) {
            for (Map.Entry<String, JsonNode> field : results.properties()) {
                run.pageResults.put(field.getKey(), field.getValue().asText());
            }
        }

        JsonNode api = root.path("api");
        run.apiTotalRequests = intOrNull(api, "totalRequests");
        run.apiSuccessful = intOrNull(api, "successful");
        run.apiFailed = intOrNull(api, "failed");
        run.apiTimeouts = intOrNull(api, "timeouts");
        JsonNode statuses = api.path("statusDistribution");
        if (statuses.isObject()) {
            for (Map.Entry<String, JsonNode> field : statuses.properties()) {
                run.apiStatusDistribution.put(field.getKey(), field.getValue().asInt());
            }
        }

        run.streamFailureCount = intOrNull(root, "streamFailureCount");
        run.apiFailureCount = intOrNull(root, "apiFailureCount");

        String evidenceFolder = text(root, "evidenceFolder");
        if (evidenceFolder != null && !evidenceFolder.isBlank()) {
            run.evidenceFolder = evidenceFolder;
        }
    }

    private static void applyApiFailures(SoakRunSummary run, JsonNode array) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            SoakRunSummary.ApiFailureRecord record = new SoakRunSummary.ApiFailureRecord();
            record.timestamp = text(node, "timestamp");
            record.page = text(node, "page");
            record.operation = text(node, "operation");
            record.method = text(node, "method");
            JsonNode status = node.path("status");
            record.status = status.isMissingNode() || status.isNull() ? null : status.asText();
            record.statusText = text(node, "statusText");
            record.url = text(node, "url");
            record.screenshot = text(node, "screenshot");
            Integer occurrences = intOrNull(node, "occurrences");
            record.occurrences = occurrences == null ? 1 : occurrences;
            run.apiFailureRecords.add(record);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.intValue();
    }
}
