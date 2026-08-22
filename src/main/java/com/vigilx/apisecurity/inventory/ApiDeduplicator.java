package com.vigilx.apisecurity.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses a list of {@link ApiDefinition} down to one entry per {@link ApiDefinition#comparisonKey()}.
 *
 * <p>The first entry seen for a key is kept as the representative sample; every later repeat of the
 * same key only bumps its occurrence counter, never adds a second entry.
 */
public final class ApiDeduplicator {

    private ApiDeduplicator() {
    }

    public static List<ApiDefinition> deduplicate(List<ApiDefinition> definitions) {
        Map<String, ApiDefinition> byKey = new LinkedHashMap<>();
        for (ApiDefinition definition : definitions) {
            ApiDefinition existing = byKey.get(definition.comparisonKey());
            if (existing == null) {
                byKey.put(definition.comparisonKey(), definition);
            } else {
                existing.addOccurrence();
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
