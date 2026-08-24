package com.vigilx.apisecurity.execution;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome of one REST Assured functional check against a single API. */
public final class ApiTestResult {

    public enum Outcome { PASS, FAIL, SKIPPED, NOT_EXECUTED }

    private final String timestamp;
    private final String method;
    private final String host;
    private final String path;
    private final String url;
    private final int expectedStatus;
    private final int actualStatus;
    private final long responseTimeMs;
    private final boolean withinResponseTimeThreshold;
    private final String contentType;
    private final Outcome outcome;
    private final String note;

    public ApiTestResult(String timestamp, String method, String host, String path, String url,
                         int expectedStatus, int actualStatus, long responseTimeMs,
                         boolean withinResponseTimeThreshold, String contentType, Outcome outcome, String note) {
        this.timestamp = timestamp;
        this.method = method;
        this.host = host;
        this.path = path;
        this.url = url;
        this.expectedStatus = expectedStatus;
        this.actualStatus = actualStatus;
        this.responseTimeMs = responseTimeMs;
        this.withinResponseTimeThreshold = withinResponseTimeThreshold;
        this.contentType = contentType;
        this.outcome = outcome;
        this.note = note;
    }

    public String method() { return method; }
    public String host() { return host; }
    public String path() { return path; }
    public int expectedStatus() { return expectedStatus; }
    public int actualStatus() { return actualStatus; }
    public long responseTimeMs() { return responseTimeMs; }
    public Outcome outcome() { return outcome; }
    public boolean isPassed() { return outcome == Outcome.PASS; }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp);
        map.put("method", method);
        map.put("host", host);
        map.put("path", path);
        map.put("url", url);
        map.put("expectedStatus", expectedStatus);
        map.put("actualStatus", actualStatus);
        map.put("responseTimeMs", responseTimeMs);
        map.put("withinResponseTimeThreshold", withinResponseTimeThreshold);
        map.put("contentType", contentType == null ? "" : contentType);
        map.put("outcome", outcome.name());
        map.put("note", note == null ? "" : note);
        return map;
    }
}
