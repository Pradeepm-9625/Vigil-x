package com.vigilx.apisecurity.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.performance.CrudLifecycleGrouper.CrudGroup;

/**
 * Reads a load-test {@code .jtl} results file directly (its own small parser, deliberately not a
 * change to {@link JtlResultParser}: that class is already relied on by {@code JmeterPerformanceTest}
 * and other consumers, and this needs different information from the same file - a real PASS/FAIL
 * per sample, not just an aggregate) and judges every sample against the one real "expected result"
 * available: the HTTP status this exact API was actually observed returning in the original capture
 * ({@link ApiDefinition#sampleStatus()}).
 *
 * <p>A sample is PASS only when JMeter's own {@code success} column is {@code true} (so a connection
 * failure, timeout, or a failed in-JMX Response Assertion already fails it) AND its live response
 * code equals the API's captured {@code sampleStatus()} - never "any response at all", and never
 * "any 2xx". An API with no real captured status (never actually observed, {@code sampleStatus() <= 0})
 * is judged on JMeter's {@code success} column alone, since there is no real expectation to compare
 * against - reported honestly via {@link ApiResult#expectedStatusKnown}, not invented.
 */
public final class LoadTestResultAnalyzer {

    private LoadTestResultAnalyzer() {
    }

    /** One real HTTP sample JMeter actually sent, judged against this API's own captured expectation. */
    public static final class SampleResult {
        public final String method;
        public final String endpoint;
        public final int expectedStatus;
        public final boolean expectedStatusKnown;
        public final int actualStatus;
        public final double elapsedMs;
        public final long timestampMs;
        public final boolean jmeterSuccess;
        public final String failureMessage;
        public final boolean passed;

        SampleResult(String method, String endpoint, int expectedStatus, boolean expectedStatusKnown,
                    int actualStatus, double elapsedMs, long timestampMs, boolean jmeterSuccess,
                    String failureMessage, boolean passed) {
            this.method = method;
            this.endpoint = endpoint;
            this.expectedStatus = expectedStatus;
            this.expectedStatusKnown = expectedStatusKnown;
            this.actualStatus = actualStatus;
            this.elapsedMs = elapsedMs;
            this.timestampMs = timestampMs;
            this.jmeterSuccess = jmeterSuccess;
            this.failureMessage = failureMessage;
            this.passed = passed;
        }
    }

    /** Every sample for one API (one method+endpoint label), rolled up. */
    public static final class ApiResult {
        public final String method;
        public final String endpoint;
        public boolean expectedStatusKnown;
        public int expectedStatus;
        public int requests;
        public int passed;
        public int failed;
        public double totalMs;
        public double minMs = Double.MAX_VALUE;
        public double maxMs;
        public long firstTimestampMs = Long.MAX_VALUE;
        public long lastTimestampMs;
        public final Map<Integer, Integer> statusCodeCounts = new LinkedHashMap<>();
        public final List<SampleResult> failures = new ArrayList<>();

        ApiResult(String method, String endpoint) {
            this.method = method;
            this.endpoint = endpoint;
        }

        public double passPercent() { return requests == 0 ? 0 : 100.0 * passed / requests; }
        public double failPercent() { return requests == 0 ? 0 : 100.0 * failed / requests; }
        public double avgMs() { return requests == 0 ? 0 : totalMs / requests; }
        public double throughputPerSecond() {
            double seconds = (lastTimestampMs - firstTimestampMs) / 1000.0;
            return seconds > 0 ? requests / seconds : 0;
        }
    }

    /** One CREATE/UPDATE/DELETE step's real outcome, for the CRUD lifecycle report section. */
    public static final class CrudStepResult {
        public final String method;
        public final String endpoint;
        /** {@code null} when this step was never part of the captured inventory for this resource. */
        public final String status;
        public final String reason;

        CrudStepResult(String method, String endpoint, String status, String reason) {
            this.method = method;
            this.endpoint = endpoint;
            this.status = status;
            this.reason = reason;
        }
    }

    /** One resource's real CREATE -&gt; UPDATE -&gt; DELETE outcome, for the CRUD lifecycle report section. */
    public static final class CrudLifecycleResult {
        public final String resourceName;
        public final String capturedId;
        public final CrudStepResult create;
        public final CrudStepResult update;
        public final CrudStepResult delete;

        CrudLifecycleResult(String resourceName, String capturedId, CrudStepResult create, CrudStepResult update,
                            CrudStepResult delete) {
            this.resourceName = resourceName;
            this.capturedId = capturedId;
            this.create = create;
            this.update = update;
            this.delete = delete;
        }
    }

    /** One captured-but-never-executed API, with the real reason - never silently dropped. */
    public static final class SkippedApi {
        public final String method;
        public final String endpoint;
        public final String reason;

        SkippedApi(String method, String endpoint, String reason) {
            this.method = method;
            this.endpoint = endpoint;
            this.reason = reason;
        }
    }

    /** Whole-run result: every API's rollup, in first-seen order, plus the overall totals. */
    public static final class AnalysisResult {
        public final Map<String, ApiResult> byApi = new LinkedHashMap<>();
        public final List<CrudLifecycleResult> crudLifecycles = new ArrayList<>();
        public final List<SkippedApi> skipped = new ArrayList<>();
        public int totalRequests;
        public int totalPassed;
        public int totalFailed;
        public long startTimestampMs = Long.MAX_VALUE;
        public long endTimestampMs;
        /** Set by the caller from the real pre-filter combined inventory size - this class has no
         *  access to it, only to what it was actually asked to test. */
        public int totalCaptured;

        public double passPercent() { return totalRequests == 0 ? 0 : 100.0 * totalPassed / totalRequests; }
        public double failPercent() { return totalRequests == 0 ? 0 : 100.0 * totalFailed / totalRequests; }

        /**
         * Every distinct {@link ApiResult}, by identity - {@code byApi} deliberately maps MORE THAN
         * ONE key onto the same object for a chained UPDATE/DELETE (its plain "METHOD /path" key and
         * its real JMX-label key both resolve to one canonical result, see
         * {@link #aliasChainedLabel}), so counting/iterating {@code byApi.values()} directly would
         * double count and double print those APIs. This is the one place that de-duplication happens.
         */
        public java.util.Collection<ApiResult> distinctApiResults() {
            return new java.util.LinkedHashSet<>(byApi.values());
        }

        public int totalIncluded() { return distinctApiResults().size(); }
        public int totalExecuted() { return (int) distinctApiResults().stream().filter(a -> a.requests > 0).count(); }
        public int totalSkipped() { return totalIncluded() - totalExecuted(); }
    }

    public static AnalysisResult analyze(Path jtlFile, List<ApiDefinition> testedApis) throws IOException {
        return analyze(jtlFile, testedApis, java.util.List.of());
    }

    public static AnalysisResult analyze(Path jtlFile, List<ApiDefinition> testedApis, List<CrudGroup> crudGroups)
            throws IOException {
        AnalysisResult result = new AnalysisResult();
        Map<String, ApiDefinition> byLabel = new LinkedHashMap<>();
        for (ApiDefinition definition : testedApis) {
            String plainKey = definition.method() + " " + definition.normalizedPath();
            byLabel.put(plainKey, definition);
            result.byApi.put(plainKey, new ApiResult(definition.method(), definition.normalizedPath()));
        }
        // Every chained UPDATE/DELETE sampler is labeled "METHOD /path (${var})" in the JMX (see
        // LoadTestPlanBuilder.appendHttpSampler) so its evidence is visibly tied to the CRUD lifecycle
        // that produced it - alias that exact label back onto the SAME ApiResult/ApiDefinition the
        // plain key already resolved to, so a chained write's real samples land on the one canonical
        // entry for that API instead of silently creating a second, orphaned "skipped" entry.
        for (CrudGroup group : crudGroups) {
            aliasChainedLabel(byLabel, result.byApi, group.update(), group.variableName());
            aliasChainedLabel(byLabel, result.byApi, group.delete(), group.variableName());
        }

        if (!Files.exists(jtlFile)) {
            buildCrudLifecycleSection(result, crudGroups);
            buildSkippedSection(result);
            return result;
        }
        // A real, quote-aware CSV parse of the WHOLE file - not Files.readAllLines() + a per-line
        // regex split: JMeter's own failure/assertion-failure message column can (and, confirmed
        // live, does) contain a literal embedded newline inside a quoted field, which
        // readAllLines() would wrongly treat as the end of that record, corrupting every column
        // after it for that row and the row that follows. parseCsv() below only ever treats a
        // newline as a record separator when it is not inside an open quote.
        List<List<String>> rows = parseCsv(Files.readString(jtlFile, java.nio.charset.StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            return result;
        }

        String[] header = rows.get(0).toArray(new String[0]);
        int labelIdx = indexOf(header, "label");
        int elapsedIdx = indexOf(header, "elapsed");
        int successIdx = indexOf(header, "success");
        int codeIdx = indexOf(header, "responseCode");
        int messageIdx = indexOf(header, "responseMessage");
        int failureMessageIdx = indexOf(header, "failureMessage");
        int timestampIdx = indexOf(header, "timeStamp");
        if (labelIdx < 0 || elapsedIdx < 0) {
            return result;
        }

        for (int i = 1; i < rows.size(); i++) {
            List<String> fields = rows.get(i);
            if (fields.size() <= Math.max(labelIdx, elapsedIdx)) {
                continue;
            }
            String label = fields.get(labelIdx);
            double elapsed;
            try {
                elapsed = Double.parseDouble(fields.get(elapsedIdx));
            } catch (NumberFormatException exception) {
                continue;
            }
            boolean jmeterSuccess = successIdx >= 0 && successIdx < fields.size()
                    && Boolean.parseBoolean(fields.get(successIdx));
            int actualStatus = parseIntSafe(codeIdx >= 0 && codeIdx < fields.size() ? fields.get(codeIdx) : null, -1);
            long timestamp = parseLongSafe(timestampIdx >= 0 && timestampIdx < fields.size()
                    ? fields.get(timestampIdx) : null, 0);
            String failureMessage = firstNonBlank(
                    failureMessageIdx >= 0 && failureMessageIdx < fields.size() ? fields.get(failureMessageIdx) : null,
                    messageIdx >= 0 && messageIdx < fields.size() ? fields.get(messageIdx) : null);

            ApiDefinition definition = byLabel.get(label);
            String method = definition != null ? definition.method() : firstToken(label);
            String endpoint = definition != null ? definition.normalizedPath() : label;
            boolean expectedKnown = definition != null && definition.sampleStatus() > 0;
            int expectedStatus = expectedKnown ? definition.sampleStatus() : -1;

            boolean passed = jmeterSuccess && (!expectedKnown || actualStatus == expectedStatus);

            SampleResult sample = new SampleResult(method, endpoint, expectedStatus, expectedKnown,
                    actualStatus, elapsed, timestamp, jmeterSuccess, failureMessage, passed);

            ApiResult apiResult = result.byApi.computeIfAbsent(label, key -> new ApiResult(method, endpoint));
            apiResult.expectedStatusKnown = expectedKnown;
            apiResult.expectedStatus = expectedStatus;
            apiResult.requests++;
            apiResult.totalMs += elapsed;
            apiResult.minMs = Math.min(apiResult.minMs, elapsed);
            apiResult.maxMs = Math.max(apiResult.maxMs, elapsed);
            if (timestamp > 0) {
                apiResult.firstTimestampMs = Math.min(apiResult.firstTimestampMs, timestamp);
                apiResult.lastTimestampMs = Math.max(apiResult.lastTimestampMs, timestamp);
            }
            apiResult.statusCodeCounts.merge(actualStatus, 1, Integer::sum);
            if (passed) {
                apiResult.passed++;
            } else {
                apiResult.failed++;
                apiResult.failures.add(sample);
            }

            result.totalRequests++;
            if (passed) {
                result.totalPassed++;
            } else {
                result.totalFailed++;
            }
            if (timestamp > 0) {
                result.startTimestampMs = Math.min(result.startTimestampMs, timestamp);
                result.endTimestampMs = Math.max(result.endTimestampMs, timestamp);
            }
        }

        // Any configured/tested API that never produced a single sample (e.g. JMeter itself could not
        // reach it at all) still shows up with real zero counts, never silently dropped from the report.
        for (ApiResult apiResult : result.byApi.values()) {
            if (apiResult.minMs == Double.MAX_VALUE) {
                apiResult.minMs = 0;
            }
            if (apiResult.firstTimestampMs == Long.MAX_VALUE) {
                apiResult.firstTimestampMs = 0;
            }
        }
        if (result.startTimestampMs == Long.MAX_VALUE) {
            result.startTimestampMs = 0;
        }

        buildCrudLifecycleSection(result, crudGroups);
        buildSkippedSection(result);
        return result;
    }

    /**
     * Aliases a chained UPDATE/DELETE sampler's real JMX label ({@code "METHOD /path (${var})"}, see
     * {@link LoadTestPlanBuilder#appendCrudLifecycle}) onto the SAME {@link ApiResult}/{@link ApiDefinition}
     * the plain {@code "METHOD /path"} key already resolves to - so its real samples accumulate onto
     * the one canonical entry for that API, not a second, orphaned one.
     */
    private static void aliasChainedLabel(Map<String, ApiDefinition> byLabel, Map<String, ApiResult> byApi,
                                          ApiDefinition definition, String variableName) {
        if (definition == null) {
            return;
        }
        String plainKey = definition.method() + " " + definition.normalizedPath();
        String chainedKey = plainKey + " [chained:" + variableName + "]";
        byLabel.put(chainedKey, definition);
        ApiResult canonical = byApi.get(plainKey);
        if (canonical != null) {
            byApi.put(chainedKey, canonical);
        }
    }

    /**
     * Builds the real per-resource CREATE -&gt; UPDATE -&gt; DELETE report from whatever
     * {@link #analyze} already recorded for each step's own (possibly aliased) {@link ApiResult} -
     * never a separate re-derivation. A step absent from the real captured inventory for this
     * resource (e.g. no DELETE was ever observed) is reported as such, not silently omitted; a step
     * present but never executed because an earlier step in the SAME lifecycle produced no passing
     * sample is reported "NOT EXECUTED" with that real reason, matching the brief's own example.
     */
    private static void buildCrudLifecycleSection(AnalysisResult result, List<CrudGroup> crudGroups) {
        for (CrudGroup group : crudGroups) {
            CrudStepResult createStep = stepResultOf(result, group.create(), null, null);
            boolean createPassed = createStep != null && "PASS".equals(createStep.status);
            CrudStepResult updateStep = stepResultOf(result, group.update(),
                    createPassed ? null : "CREATE did not pass, so no real id was available to update",
                    "Not captured for this resource in the inventory");
            CrudStepResult deleteStep = stepResultOf(result, group.delete(),
                    createPassed ? null : "CREATE did not pass, so no real id was available to delete",
                    "Not captured for this resource in the inventory");
            result.crudLifecycles.add(new CrudLifecycleResult(group.resourceName(), "${" + group.variableName() + "}",
                    createStep, updateStep, deleteStep));
        }
    }

    private static CrudStepResult stepResultOf(AnalysisResult result, ApiDefinition definition,
                                               String notExecutedReason, String notCapturedReason) {
        if (definition == null) {
            return new CrudStepResult(null, null, "NOT CAPTURED", notCapturedReason);
        }
        String key = definition.method() + " " + definition.normalizedPath();
        // The chained key (with the "[chained:var]" suffix) is what real samples for update/delete
        // land under (see aliasChainedLabel) - the plain key covers create, which is never chained.
        String chainedKeyGuess = null;
        for (Map.Entry<String, ApiResult> entry : result.byApi.entrySet()) {
            if (entry.getValue() != null && entry.getKey().startsWith(key + " [chained:")) {
                chainedKeyGuess = entry.getKey();
                break;
            }
        }
        ApiResult apiResult = result.byApi.get(chainedKeyGuess != null ? chainedKeyGuess : key);
        if (apiResult == null || apiResult.requests == 0) {
            if (notExecutedReason != null) {
                return new CrudStepResult(definition.method(), definition.normalizedPath(), "NOT EXECUTED", notExecutedReason);
            }
            return new CrudStepResult(definition.method(), definition.normalizedPath(), "NOT EXECUTED",
                    "No sample recorded within the configured test duration");
        }
        String status = apiResult.failed == 0 ? "PASS" : apiResult.passed > 0 ? "PARTIAL (" + apiResult.passed
                + "/" + apiResult.requests + " passed)" : "FAIL";
        return new CrudStepResult(definition.method(), definition.normalizedPath(), status, null);
    }

    /**
     * Every included API that produced zero real samples, with a best-effort but honest reason -
     * never silently absent from the report. {@code Included == Executed + Skipped} always holds by
     * construction, since both counts are read straight off the same {@code byApi} map this method
     * itself does not modify.
     */
    private static void buildSkippedSection(AnalysisResult result) {
        for (ApiResult apiResult : result.distinctApiResults()) {
            if (apiResult.requests > 0) {
                continue;
            }
            result.skipped.add(new SkippedApi(apiResult.method, apiResult.endpoint,
                    "No sample recorded within the configured test duration - either the run window ended "
                            + "before this sampler's turn, or (for a chained UPDATE/DELETE) its CREATE step "
                            + "did not produce a usable id this iteration."));
        }
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A real RFC4180-style CSV parse of the whole file content: a newline only ends a record when
     * it is not inside an open quote, and {@code ""} inside a quoted field is a literal quote
     * character - the exact case a per-line regex split cannot handle, and the exact shape of
     * JMeter's own multi-line assertion-failure message column.
     */
    private static List<List<String>> parseCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowStarted = false;
        int length = content.length();
        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < length && content.charAt(i + 1) == '"') {
                    field.append('"');
                    i += 2;
                } else if (c == '"') {
                    inQuotes = false;
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                rowStarted = true;
                i++;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                rowStarted = true;
                i++;
            } else if (c == '\r') {
                i++;
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                rowStarted = false;
                i++;
            } else {
                field.append(c);
                rowStarted = true;
                i++;
            }
        }
        if (rowStarted || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static int parseIntSafe(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseLongSafe(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String firstToken(String label) {
        int space = label.indexOf(' ');
        return space > 0 ? label.substring(0, space) : label;
    }
}
