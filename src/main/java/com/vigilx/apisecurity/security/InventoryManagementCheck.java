package com.vigilx.apisecurity.security;

import java.util.List;

import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;

/**
 * API9 - Improper Inventory Management.
 *
 * <p>Reuses the already-computed {@link com.vigilx.apisecurity.inventory.ApiComparisonService} result
 * rather than re-deriving it: that comparison <em>is</em> this category's evidence. An API discovered
 * in real traffic (the HAR) but absent from the application's own documented/monitored surface
 * (ApiMonitor) is exactly what "improper inventory management" means.
 */
public final class InventoryManagementCheck implements SecurityCheck {

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API9_IMPROPER_INVENTORY;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        var comparison = context.comparisonResult();
        int missing = comparison.missingFromMonitor().size();
        int total = comparison.combinedUnique().size();

        boolean pass = missing == 0;
        return List.of(new SecurityFinding(SecurityHttp.now(), category(),
                "Every real API endpoint is captured by the application's own monitoring", null, null,
                pass ? Verdict.PASS : Verdict.FAIL,
                pass ? Severity.INFO : (missing > total / 3 ? Severity.HIGH : Severity.MEDIUM),
                pass ? "All " + total + " known API endpoints are already captured by ApiMonitor."
                        : missing + " of " + total + " known API endpoints (from a HAR trace) are not "
                                + "captured by the application's own API monitoring - see "
                                + "missing-api-report.csv for the full list.",
                "ApiMonitor=" + comparison.monitorApis().size() + ", HAR-unique=" + comparison.harApis().size()
                        + ", missing=" + missing));
    }
}
