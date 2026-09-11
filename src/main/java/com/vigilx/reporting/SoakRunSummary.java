package com.vigilx.reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One completed soak run, as parsed from its {@code run-<timestamp>/final-report/soak-test-summary.json}
 * (plus, when present, its sibling {@code api-failures/api-failures.json}).
 *
 * <p>Every field is nullable/optional on purpose: a run generated before the consolidated report
 * existed, or one whose evidence folder is missing a file, still needs to show up in the consolidated
 * report with whatever data survives. Callers must not assume a field is populated - render {@code
 * null}/empty as "N/A", never as zero or PASS. See {@link SoakRunSummaryStore} for how these are built.
 */
public final class SoakRunSummary {

    /** One structured API failure, read from a run's {@code api-failures.json}. */
    public static final class ApiFailureRecord {
        public String timestamp;
        public String page;
        public String operation;
        public String method;
        public String status;
        public String statusText;
        public String url;
        public String screenshot;
        public int occurrences = 1;
    }

    /** Folder name under {@code target/soak-test}, e.g. {@code run-20260820-155725}. Always present. */
    public String runFolder;

    /** Unique execution ID (e.g. {@code SOAK-2026-08-20_155725_627}) when the run recorded one. */
    public String runId;

    /** True when {@code runId} is a fallback (the run folder name) rather than the real execution ID. */
    public boolean runIdIsFallback;

    public String startTime;
    public String endTime;
    public String duration;

    /** PASS / FAIL as the run itself recorded it, or {@code null} when unknown. Never inferred. */
    public String overallResult;

    public Integer pagesTotal;
    public Integer pagesPassed;
    public Integer pagesFailed;
    public List<String> failedPages = new ArrayList<>();

    /** Every page's own status for this run (name -> status string), when the run recorded it. */
    public Map<String, String> pageResults = new LinkedHashMap<>();

    public Integer apiTotalRequests;
    public Integer apiSuccessful;
    public Integer apiFailed;
    public Integer apiTimeouts;
    public Map<String, Integer> apiStatusDistribution = new LinkedHashMap<>();

    public Integer streamFailureCount;
    public Integer apiFailureCount;

    /** Relative path (from the project root) to this run's own, unmodified HTML report. */
    public String individualReportPath;

    public String evidenceFolder;

    /** Structured per-API failures for this run; empty when {@code api-failures.json} is unavailable. */
    public List<ApiFailureRecord> apiFailureRecords = new ArrayList<>();

    /**
     * True when {@code soak-test-summary.json} itself could not be read/parsed for this run folder.
     * The run is still included (never dropped), just with every other field left at its default/N-A.
     */
    public boolean summaryUnavailable;
}
