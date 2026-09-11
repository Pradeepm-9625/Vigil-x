package com.vigilx.apisecurity.inventory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiComparisonService.ComparisonResult;
import com.vigilx.apisecurity.inventory.MissingApiRegistryUpdater.RegenerationResult;

/**
 * Builds the FINAL UNIQUE API INVENTORY (brief Phase 7) the same way every consumer (REST Assured,
 * OWASP security checks, ZAP, JMeter) needs it:
 *
 * <pre>
 * ApiMonitor APIs + HAR APIs -&gt; normalize -&gt; compare -&gt; dedupe
 *   -&gt; revalidate/regenerate MissingApiRegistry (real code, not a one-off script)
 *   -&gt; ApiMonitor APIs + (only the HAR gaps the registry now confirms) -&gt; dedupe again
 *   -&gt; FINAL UNIQUE API INVENTORY, enriched with ID/category/eligibility (FinalApiRecord)
 * </pre>
 *
 * <p>Centralized so every consumer builds the inventory identically; before this existed, each test
 * class duplicated this exact assembly logic.
 */
public final class CombinedInventoryBuilder {

    private CombinedInventoryBuilder() {
    }

    public static final class Built {
        public final ComparisonResult comparison;
        public final List<ApiDefinition> combined;
        public final List<FinalApiRecord> finalRecords;
        public final RegenerationResult registryRegeneration;

        Built(ComparisonResult comparison, List<ApiDefinition> combined, List<FinalApiRecord> finalRecords,
              RegenerationResult registryRegeneration) {
            this.comparison = comparison;
            this.combined = combined;
            this.finalRecords = finalRecords;
            this.registryRegeneration = registryRegeneration;
        }
    }

    public static Built build(Path harFile, Path monitorInventoryFile) throws IOException {
        List<ApiDefinition> harApis = HarParser.parse(harFile);
        List<ApiDefinition> monitorApis = ApiMonitorInventoryReader.readFromFile(monitorInventoryFile);
        ComparisonResult comparison = ApiComparisonService.compare(monitorApis, harApis);

        // Revalidate/regenerate the registry from this real comparison (brief Phase 6/34) - actual
        // repeatable code, not a manual one-off script. Best-effort: a read-only filesystem or other
        // I/O problem here must not block building the inventory from whatever registry already exists.
        RegenerationResult registryRegeneration;
        try {
            registryRegeneration = MissingApiRegistryUpdater.regenerate(comparison, MissingApiRegistry.sourceFile());
            if (!registryRegeneration.added.isEmpty() || !registryRegeneration.removed.isEmpty()) {
                System.out.println("[MISSING API REGISTRY] Regenerated: +" + registryRegeneration.added.size()
                        + " added, -" + registryRegeneration.removed.size() + " removed (now captured by "
                        + "ApiMonitor), " + registryRegeneration.unchanged + " unchanged.");
            }
        } catch (Exception exception) {
            System.err.println("[MISSING API REGISTRY] Could not regenerate: " + exception.getMessage()
                    + " - continuing with the registry as it already is.");
            registryRegeneration = new RegenerationResult();
        }

        List<ApiDefinition> registryApis = MissingApiRegistry.getMissingApis();

        List<ApiDefinition> combined = new ArrayList<>(comparison.monitorApis());
        for (ApiDefinition definition : registryApis) {
            boolean alreadyPresent = combined.stream()
                    .anyMatch(existing -> existing.comparisonKey().equals(definition.comparisonKey()));
            if (!alreadyPresent) {
                combined.add(definition);
            }
        }
        // Explicit second dedup pass (brief Phase 7: "run deduplication again") - structurally visible
        // rather than only implicit in the alreadyPresent check above.
        combined = ApiDeduplicator.deduplicate(combined);

        List<FinalApiRecord> finalRecords = buildFinalRecords(combined, comparison);

        return new Built(comparison, combined, finalRecords, registryRegeneration);
    }

    private static List<FinalApiRecord> buildFinalRecords(List<ApiDefinition> combined, ComparisonResult comparison) {
        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        Set<String> duplicateKeys = new HashSet<>();
        for (ApiDefinition definition : comparison.duplicates()) {
            duplicateKeys.add(definition.comparisonKey());
        }
        Set<String> harMissingKeys = new HashSet<>();
        for (ApiDefinition definition : comparison.missingFromMonitor()) {
            harMissingKeys.add(definition.comparisonKey());
        }

        List<FinalApiRecord> records = new ArrayList<>();
        for (ApiDefinition definition : combined) {
            FinalApiSource source;
            if (duplicateKeys.contains(definition.comparisonKey())) {
                source = FinalApiSource.BOTH;
            } else if (harMissingKeys.contains(definition.comparisonKey())) {
                source = FinalApiSource.HAR_MISSING;
            } else {
                source = FinalApiSource.APIMONITOR;
            }

            boolean eligibleForFunctional = policy.isEligibleForFunctionalTest(definition);
            String exclusionReason = policy.ineligibilityReason(definition);
            boolean eligibleForSecurity = policy.isEligibleForNegativeTest(definition);
            String securityExclusionReason = policy.negativeIneligibilityReason(definition);
            records.add(new FinalApiRecord(definition, source, eligibleForFunctional, exclusionReason,
                    eligibleForSecurity, securityExclusionReason));
        }
        return records;
    }
}
