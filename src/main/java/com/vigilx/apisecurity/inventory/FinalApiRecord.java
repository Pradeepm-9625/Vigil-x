package com.vigilx.apisecurity.inventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of the FINAL UNIQUE API INVENTORY (brief Phase 7): an {@link ApiDefinition} enriched with
 * everything downstream test execution and reporting needs - a stable ID, its business category, and
 * per-test-type eligibility with a recorded reason whenever an API is deliberately not executed.
 *
 * <p>Built once, by {@link CombinedInventoryBuilder}, from real evidence only:
 * {@link ApiDefinition} already carries only observed data (never fabricated), and eligibility here
 * is computed straight from {@link com.vigilx.apisecurity.execution.ApiExecutionPolicy} - the same
 * policy every runner (REST Assured, OWASP, ZAP, JMeter) already enforces, so this record's
 * eligibility flags can never drift out of sync with what actually gets executed.
 *
 * <p>Positive/performance eligibility and negative/security eligibility are deliberately two distinct
 * flags, not one: a real, successful write call would mutate live data, while a negative case (missing
 * auth, malformed body, wrong method) is constructed to be rejected before any mutation happens - so
 * write APIs are excluded from the former but not the latter.
 */
public final class FinalApiRecord {

    private final String apiId;
    private final ApiDefinition definition;
    private final FinalApiSource source;
    private final ApiCategory category;
    private final boolean eligibleForFunctionalTesting;
    private final boolean eligibleForSecurityTesting;
    private final boolean eligibleForPerformanceTesting;
    private final String exclusionReason;
    private final String securityExclusionReason;

    public FinalApiRecord(ApiDefinition definition, FinalApiSource source, boolean eligibleForFunctional,
                          String exclusionReason, boolean eligibleForSecurity, String securityExclusionReason) {
        this.apiId = generateId(definition);
        this.definition = definition;
        this.source = source;
        this.category = ApiClassifier.classify(definition);
        this.eligibleForFunctionalTesting = eligibleForFunctional;
        this.eligibleForSecurityTesting = eligibleForSecurity;
        this.eligibleForPerformanceTesting = eligibleForFunctional;
        this.exclusionReason = exclusionReason;
        this.securityExclusionReason = securityExclusionReason;
    }

    /** Stable across runs regardless of list ordering: a short hash of method+host+normalizedPath. */
    private static String generateId(ApiDefinition definition) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(definition.comparisonKey().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "API-" + hex;
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is always available on any real JVM; this is unreachable in practice.
            return "API-" + Integer.toHexString(definition.comparisonKey().hashCode());
        }
    }

    public String apiId() { return apiId; }
    public ApiDefinition definition() { return definition; }
    public FinalApiSource source() { return source; }
    public ApiCategory category() { return category; }
    public boolean eligibleForFunctionalTesting() { return eligibleForFunctionalTesting; }
    public boolean eligibleForSecurityTesting() { return eligibleForSecurityTesting; }
    public boolean eligibleForPerformanceTesting() { return eligibleForPerformanceTesting; }
    public String exclusionReason() { return exclusionReason; }
    public String securityExclusionReason() { return securityExclusionReason; }

    /** "required" / "not required" / "unknown" - always from real observed evidence, never guessed. */
    public String authRequiredLabel() {
        Boolean observed = definition.authObservedInSample();
        if (observed == null) {
            return "unknown (not observed)";
        }
        return observed ? "required (observed)" : "not required (observed)";
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("apiId", apiId);
        map.put("method", definition.method());
        map.put("normalizedEndpoint", definition.normalizedPath());
        map.put("sampleUrl", definition.host() + definition.samplePath()
                + (definition.sampleQuery() == null || definition.sampleQuery().isBlank() ? "" : "?" + definition.sampleQuery()));
        map.put("source", source.name());
        map.put("apiMonitorPresent", source != FinalApiSource.HAR_MISSING ? "YES" : "NO");
        map.put("harPresent", source != FinalApiSource.APIMONITOR ? "YES" : "NO");
        map.put("authenticationRequired", authRequiredLabel());
        map.put("hasSampleRequestBody", definition.hasSampleRequestBody());
        map.put("category", category.name());
        map.put("eligibleForFunctionalTesting", eligibleForFunctionalTesting);
        map.put("eligibleForSecurityTesting", eligibleForSecurityTesting);
        map.put("eligibleForPerformanceTesting", eligibleForPerformanceTesting);
        map.put("exclusionReason", exclusionReason == null ? "" : exclusionReason);
        map.put("securityExclusionReason", securityExclusionReason == null ? "" : securityExclusionReason);
        return map;
    }
}
