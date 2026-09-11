package com.vigilx.apisecurity.execution;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.apisecurity.inventory.ApiDefinition;

/**
 * Decides which APIs from the combined inventory are safe to execute automatically - under two
 * distinct policies, by explicit sign-off, because positive and negative testing carry very
 * different risk for a write API:
 *
 * <ul>
 *   <li>{@link #isEligibleForFunctionalTest(ApiDefinition)} (positive/functional/performance execution,
 *       used by REST Assured, ZAP, JMeter) - GET only. Calling a write endpoint with a real,
 *       successful request would create/mutate/delete real data with no built-in rollback.</li>
 *   <li>{@link #isEligibleForNegativeTest(ApiDefinition)} (the negative-test engine) - every method,
 *       GET included. A negative case (missing/invalid auth, malformed body, wrong Content-Type, an
 *       unsupported method) is specifically constructed to be rejected before any real mutation
 *       happens; a well-behaved API never reaches its write logic on such a request. This is what
 *       makes negative testing on write APIs safe in a way positive testing is not.</li>
 * </ul>
 *
 * <p>On top of both, a data-driven exclusion list ({@code apisecurity/excluded-apis.json} on the
 * classpath) lets specific endpoints be opted out of either policy too - e.g. one later found to
 * carry a real side effect even under a negative probe - without ever requiring a code change.
 */
public final class ApiExecutionPolicy {

    private static final String EXCLUSIONS_RESOURCE = "apisecurity/excluded-apis.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<String> excludedKeys;

    private ApiExecutionPolicy(Set<String> excludedKeys) {
        this.excludedKeys = excludedKeys;
    }

    public static ApiExecutionPolicy loadDefault() {
        return new ApiExecutionPolicy(loadExclusions());
    }

    /** True only for GET endpoints not present in the exclusion list. */
    public boolean isEligibleForFunctionalTest(ApiDefinition definition) {
        if (!"GET".equals(definition.method())) {
            return false;
        }
        return !excludedKeys.contains(definition.comparisonKey());
    }

    /** Human-readable reason a given API is not auto-executed; {@code null} when it is eligible. */
    public String ineligibilityReason(ApiDefinition definition) {
        if (!"GET".equals(definition.method())) {
            return "write API (" + definition.method() + ") - not auto-executed by default policy "
                    + "(creates/mutates/deletes real data)";
        }
        if (excludedKeys.contains(definition.comparisonKey())) {
            return "explicitly excluded via " + EXCLUSIONS_RESOURCE;
        }
        return null;
    }

    /**
     * True for any method (GET included) not explicitly excluded. Every case the negative-test
     * engine sends is constructed to be rejected before reaching real mutation logic, so unlike
     * {@link #isEligibleForFunctionalTest}, write methods are not blanket-excluded here.
     */
    public boolean isEligibleForNegativeTest(ApiDefinition definition) {
        return !excludedKeys.contains(definition.comparisonKey());
    }

    /** Human-readable reason a given API is not negative-tested; {@code null} when it is eligible. */
    public String negativeIneligibilityReason(ApiDefinition definition) {
        if (excludedKeys.contains(definition.comparisonKey())) {
            return "explicitly excluded via " + EXCLUSIONS_RESOURCE;
        }
        return null;
    }

    private static Set<String> loadExclusions() {
        Set<String> keys = new HashSet<>();
        try (InputStream stream = ApiExecutionPolicy.class.getClassLoader().getResourceAsStream(EXCLUSIONS_RESOURCE)) {
            if (stream == null) {
                return keys;
            }
            JsonNode root = MAPPER.readTree(stream);
            for (JsonNode entry : root.path("excluded")) {
                String method = entry.path("method").asText("GET").toUpperCase(Locale.ROOT);
                String host = entry.path("host").asText("");
                String path = entry.path("path").asText("/");
                keys.add(method + " " + host + path);
            }
        } catch (IOException exception) {
            System.err.println("[API EXECUTION POLICY] Could not read " + EXCLUSIONS_RESOURCE + ": "
                    + exception.getMessage());
        }
        return keys;
    }
}
