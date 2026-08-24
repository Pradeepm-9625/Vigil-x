package com.vigilx.apisecurity.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the API inventory {@code ApiMonitor} actually captured against a HAR trace, by
 * {@link ApiDefinition#comparisonKey()} (HTTP method + host + normalized path).
 *
 * <p>Never treats the HAR as the inventory: everything ApiMonitor already captured is used as-is;
 * only endpoints present in the HAR but absent from ApiMonitor are ever surfaced as "missing", and
 * only those belong in {@link MissingApiRegistry}.
 */
public final class ApiComparisonService {

    private ApiComparisonService() {
    }

    /** Outcome of one comparison run - the real numbers, computed dynamically, never hard-coded. */
    public static final class ComparisonResult {
        private final List<ApiDefinition> monitorApis;
        private final List<ApiDefinition> harApis;
        private final List<ApiDefinition> duplicates;
        private final List<ApiDefinition> missingFromMonitor;
        private final List<ApiDefinition> combinedUnique;

        ComparisonResult(List<ApiDefinition> monitorApis, List<ApiDefinition> harApis,
                         List<ApiDefinition> duplicates, List<ApiDefinition> missingFromMonitor,
                         List<ApiDefinition> combinedUnique) {
            this.monitorApis = monitorApis;
            this.harApis = harApis;
            this.duplicates = duplicates;
            this.missingFromMonitor = missingFromMonitor;
            this.combinedUnique = combinedUnique;
        }

        /** Deduplicated APIs ApiMonitor actually captured. */
        public List<ApiDefinition> monitorApis() { return monitorApis; }

        /** Deduplicated APIs found in the HAR. */
        public List<ApiDefinition> harApis() { return harApis; }

        /** HAR APIs that ApiMonitor already captured too - never re-added, never re-executed twice. */
        public List<ApiDefinition> duplicates() { return duplicates; }

        /** HAR APIs absent from ApiMonitor - the only candidates for {@link MissingApiRegistry}. */
        public List<ApiDefinition> missingFromMonitor() { return missingFromMonitor; }

        /** ApiMonitor APIs plus the missing-from-Monitor HAR APIs, one entry per key. */
        public List<ApiDefinition> combinedUnique() { return combinedUnique; }

        public String renderSummary() {
            StringBuilder text = new StringBuilder();
            text.append("============================================================\n");
            text.append("API INVENTORY COMPARISON\n");
            text.append("============================================================\n\n");
            text.append(String.format("%-22s: %d%n", "ApiMonitor APIs", monitorApis.size()));
            text.append(String.format("%-22s: %d%n", "HAR APIs (unique)", harApis.size()));
            text.append(String.format("%-22s: %d%n", "Duplicates", duplicates.size()));
            text.append(String.format("%-22s: %d%n", "Missing from Monitor", missingFromMonitor.size()));
            text.append(String.format("%-22s: %d%n", "Combined unique APIs", combinedUnique.size()));
            text.append("============================================================\n");
            return text.toString();
        }
    }

    /**
     * Deduplicates both inputs independently, then compares by {@link ApiDefinition#comparisonKey()}.
     *
     * @param rawMonitorApis every API ApiMonitor captured (may contain repeats; deduplicated here)
     * @param rawHarApis     every API the HAR contains (may contain repeats; deduplicated here)
     */
    public static ComparisonResult compare(List<ApiDefinition> rawMonitorApis, List<ApiDefinition> rawHarApis) {
        List<ApiDefinition> monitorApis = ApiDeduplicator.deduplicate(rawMonitorApis);
        List<ApiDefinition> harApis = ApiDeduplicator.deduplicate(rawHarApis);

        Map<String, ApiDefinition> combinedByKey = new LinkedHashMap<>();
        for (ApiDefinition definition : monitorApis) {
            combinedByKey.put(definition.comparisonKey(), definition);
        }

        List<ApiDefinition> duplicates = new ArrayList<>();
        List<ApiDefinition> missingFromMonitor = new ArrayList<>();

        for (ApiDefinition harApi : harApis) {
            if (combinedByKey.containsKey(harApi.comparisonKey())) {
                duplicates.add(harApi);
            } else {
                missingFromMonitor.add(harApi);
                combinedByKey.put(harApi.comparisonKey(), harApi);
            }
        }

        return new ComparisonResult(monitorApis, harApis, duplicates, missingFromMonitor,
                new ArrayList<>(combinedByKey.values()));
    }
}
