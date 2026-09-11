package com.vigilx.apisecurity.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One result from one concrete security check against one API. {@link Verdict#NOT_ASSESSED} is a
 * first-class outcome, not a fallback: a category with no meaningful, safe, non-destructive check
 * available for this application must say so honestly rather than report a fabricated PASS.
 */
public final class SecurityFinding {

    public enum Verdict { PASS, FAIL, NOT_ASSESSED }
    public enum Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

    private final String timestamp;
    private final OwaspCategory category;
    private final String checkName;
    private final String method;
    private final String endpoint;
    private final Verdict verdict;
    private final Severity severity;
    private final String summary;
    private final String evidence;

    public SecurityFinding(String timestamp, OwaspCategory category, String checkName, String method,
                           String endpoint, Verdict verdict, Severity severity, String summary, String evidence) {
        this.timestamp = timestamp;
        this.category = category;
        this.checkName = checkName;
        this.method = method;
        this.endpoint = endpoint;
        this.verdict = verdict;
        this.severity = severity;
        this.summary = summary;
        this.evidence = evidence;
    }

    public OwaspCategory category() { return category; }
    public Verdict verdict() { return verdict; }
    public Severity severity() { return severity; }

    /**
     * True only for a genuine request/connection exception (every check's catch block uses this
     * exact "Check could not run: " prefix) - never true for a legitimate {@code NOT_ASSESSED}
     * business-rule outcome (e.g. "not applicable", "inconclusive", "needs a second account"). A
     * check's own circuit breaker must count only this, not every {@code NOT_ASSESSED} result,
     * or a run of entirely normal N/A verdicts would look like repeated server failures and trip
     * it needlessly - skipping real, still-worth-attempting APIs over nothing.
     */
    public boolean isRequestError() {
        return verdict == Verdict.NOT_ASSESSED && summary != null && summary.startsWith("Check could not run:");
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp);
        map.put("owaspCategory", category.id());
        map.put("owaspTitle", category.title());
        map.put("check", checkName);
        map.put("method", method == null ? "" : method);
        map.put("endpoint", endpoint == null ? "" : endpoint);
        map.put("verdict", verdict.name());
        map.put("severity", severity.name());
        map.put("summary", summary);
        map.put("evidence", evidence == null ? "" : evidence);
        return map;
    }
}
