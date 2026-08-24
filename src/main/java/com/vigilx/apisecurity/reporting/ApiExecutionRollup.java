package com.vigilx.apisecurity.reporting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.vigilx.apisecurity.inventory.FinalApiRecord;

/**
 * Per-API execution accounting (brief Phase 8/26): every API in the final inventory ends up here
 * exactly once, correlated against whatever the positive/negative/OWASP/ZAP/JMeter runners actually
 * did with it - built only from real result objects, never fabricated. An API this run never touched
 * still gets a row, with an honest reason (write API, circuit-breaker skip, global auth failure, ...).
 */
public final class ApiExecutionRollup {

    public enum Status { TESTED, SKIPPED_WITH_REASON, NOT_APPLICABLE_WITH_REASON, BLOCKED_WITH_REASON }

    public final FinalApiRecord record;
    public int positivePlanned;
    public int positiveExecuted;
    public int positivePass;
    public int positiveFail;
    public int negativePlanned;
    public int negativeExecuted;
    public int negativePass;
    public int negativeFail;
    public int securityTestsExecuted;
    public final TreeSet<String> owaspCategories = new TreeSet<>();
    public boolean zapTested;
    public boolean jmeterTested;
    public Status overallStatus = Status.NOT_APPLICABLE_WITH_REASON;
    public String remarks = "";

    public ApiExecutionRollup(FinalApiRecord record) {
        this.record = record;
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("apiId", record.apiId());
        map.put("method", record.definition().method());
        map.put("endpoint", record.definition().normalizedPath());
        map.put("source", record.source().name());
        map.put("apiMonitorPresent", record.toJsonMap().get("apiMonitorPresent"));
        map.put("harPresent", record.toJsonMap().get("harPresent"));
        map.put("positivePlanned", positivePlanned);
        map.put("positiveExecuted", positiveExecuted);
        map.put("positivePass", positivePass);
        map.put("positiveFail", positiveFail);
        map.put("negativePlanned", negativePlanned);
        map.put("negativeExecuted", negativeExecuted);
        map.put("negativePass", negativePass);
        map.put("negativeFail", negativeFail);
        map.put("securityTestsExecuted", securityTestsExecuted);
        map.put("owaspCategories", String.join(";", owaspCategories));
        map.put("zapTested", zapTested);
        map.put("jmeterTested", jmeterTested);
        map.put("overallStatus", overallStatus.name());
        map.put("remarks", remarks);
        return map;
    }
}
