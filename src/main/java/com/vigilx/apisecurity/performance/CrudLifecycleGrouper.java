package com.vigilx.apisecurity.performance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.vigilx.apisecurity.inventory.ApiDefinition;

/**
 * Groups the captured API inventory into real CREATE -&gt; UPDATE -&gt; DELETE lifecycles, purely
 * from the inventory's own shape - never a hard-coded resource list. A "resource" is identified by a
 * base path (e.g. {@code /devices}) that has a real captured {@code POST} to create it, paired with
 * any real captured {@code PUT}/{@code PATCH}/{@code DELETE} to {@code <base>/{id}} - the exact
 * pattern {@link com.vigilx.apisecurity.inventory.ApiNormalizer} already produces for a genuine
 * per-record detail route.
 *
 * <p>Every {@link ApiDefinition} in the input ends up in exactly one place: either as the
 * create/update/delete of a {@link CrudGroup}, or in {@link GroupingResult#standalone()} - never
 * both, and never dropped. This is what lets the load test honestly report
 * {@code Included == Executed + Skipped} with zero unaccounted-for APIs: grouping only changes HOW a
 * captured write is exercised (chained with a real dynamic id instead of run in isolation with its
 * own stale captured id), never WHETHER it is exercised at all.
 */
public final class CrudLifecycleGrouper {

    private CrudLifecycleGrouper() {
    }

    /** One real create/update/delete lifecycle for one resource, discovered from the real inventory. */
    public static final class CrudGroup {
        private final String resourceName;
        private final String variableName;
        private final ApiDefinition create;
        private final ApiDefinition update;
        private final ApiDefinition delete;

        CrudGroup(String resourceName, String variableName, ApiDefinition create, ApiDefinition update,
                 ApiDefinition delete) {
            this.resourceName = resourceName;
            this.variableName = variableName;
            this.create = create;
            this.update = update;
            this.delete = delete;
        }

        /** Human-readable resource name for reports, e.g. {@code "devices"} from {@code /devices}. */
        public String resourceName() { return resourceName; }
        /** The JMeter variable this group's real created-record id is captured into, e.g. {@code devices_id}. */
        public String variableName() { return variableName; }
        public ApiDefinition create() { return create; }
        /** May be {@code null} - not every resource has a captured update in this inventory. */
        public ApiDefinition update() { return update; }
        /** May be {@code null} - not every resource has a captured delete in this inventory. */
        public ApiDefinition delete() { return delete; }
    }

    public static final class GroupingResult {
        private final List<CrudGroup> groups;
        private final List<ApiDefinition> standalone;

        GroupingResult(List<CrudGroup> groups, List<ApiDefinition> standalone) {
            this.groups = groups;
            this.standalone = standalone;
        }

        /** Every real CREATE-&gt;UPDATE-&gt;DELETE lifecycle discovered, in first-seen order. */
        public List<CrudGroup> groups() { return groups; }
        /** Every captured API not absorbed into a lifecycle - still tested, just independently. */
        public List<ApiDefinition> standalone() { return standalone; }
    }

    public static GroupingResult group(List<ApiDefinition> apis) {
        // Index every {base}/{id} write by its base path, per host, so a create can find its real
        // update/delete siblings regardless of input order.
        Map<String, ApiDefinition> updates = new LinkedHashMap<>();
        Map<String, ApiDefinition> deletes = new LinkedHashMap<>();
        for (ApiDefinition definition : apis) {
            String base = detailBasePath(definition.normalizedPath());
            if (base == null) {
                continue;
            }
            String key = definition.host() + "|" + base;
            if ("PUT".equals(definition.method()) || "PATCH".equals(definition.method())) {
                updates.putIfAbsent(key, definition);
            } else if ("DELETE".equals(definition.method())) {
                deletes.putIfAbsent(key, definition);
            }
        }

        List<CrudGroup> groups = new ArrayList<>();
        List<ApiDefinition> standalone = new ArrayList<>();
        java.util.Set<ApiDefinition> absorbed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Map<String, Integer> variableNameCounts = new LinkedHashMap<>();

        for (ApiDefinition definition : apis) {
            if (!"POST".equals(definition.method())) {
                continue;
            }
            String key = definition.host() + "|" + definition.normalizedPath();
            ApiDefinition update = updates.get(key);
            ApiDefinition delete = deletes.get(key);
            if (update == null && delete == null) {
                continue; // A plain POST with no matching detail route - not a lifecycle, stays standalone.
            }
            String resourceName = resourceNameOf(definition.normalizedPath());
            String variableName = uniqueVariableName(resourceName, variableNameCounts);
            groups.add(new CrudGroup(resourceName, variableName, definition, update, delete));
            absorbed.add(definition);
            if (update != null) {
                absorbed.add(update);
            }
            if (delete != null) {
                absorbed.add(delete);
            }
        }

        for (ApiDefinition definition : apis) {
            if (!absorbed.contains(definition)) {
                standalone.add(definition);
            }
        }

        return new GroupingResult(groups, standalone);
    }

    /**
     * {@code /devices/{id}} -&gt; {@code /devices}; {@code /devices} (no trailing {@code {id}}) or a
     * path with {@code {id}} anywhere but the end -&gt; {@code null} (not a detail route this grouper
     * understands - deliberately conservative, matching {@link com.vigilx.apisecurity.inventory.ApiNormalizer}'s
     * own "never merge two genuinely different endpoints" stance).
     */
    private static String detailBasePath(String normalizedPath) {
        String suffix = "/{id}";
        if (normalizedPath == null || !normalizedPath.endsWith(suffix) || normalizedPath.equals(suffix)) {
            return null;
        }
        return normalizedPath.substring(0, normalizedPath.length() - suffix.length());
    }

    /** {@code /devices} -&gt; {@code "devices"}; {@code /project-hierarchy/sites} -&gt; {@code "project-hierarchy-sites"}. */
    private static String resourceNameOf(String normalizedPath) {
        String cleaned = normalizedPath.replaceAll("^/+", "").replaceAll("/+$", "").replace('/', '-');
        return cleaned.isBlank() ? "resource" : cleaned;
    }

    /** A valid, unique-per-run JMeter variable name, e.g. {@code devices_id}, {@code devices_id_2}. */
    private static String uniqueVariableName(String resourceName, Map<String, Integer> counts) {
        String base = resourceName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("(^_|_$)", "");
        if (base.isBlank()) {
            base = "resource";
        }
        base = base + "_id";
        int count = counts.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "_" + count;
    }
}
