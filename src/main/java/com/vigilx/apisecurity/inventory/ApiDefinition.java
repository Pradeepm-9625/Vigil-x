package com.vigilx.apisecurity.inventory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One API endpoint, from either source: the app's own {@code ApiMonitor} capture, a HAR trace, or
 * the manually curated {@link MissingApiRegistry}. Two instances are considered "the same API" when
 * {@link #comparisonKey()} matches - HTTP method + host + normalized path - regardless of which
 * source produced them.
 */
public final class ApiDefinition {

    public enum Source { API_MONITOR, HAR, MANUAL }

    private final String method;
    private final String host;
    private final String normalizedPath;
    private final String samplePath;
    private final String sampleQuery;
    private final int sampleStatus;
    private final Source source;
    /**
     * Whether the real, original capture of this call carried an Authorization header.
     * {@code null} means "not observed/unknown" - never guessed, only ever set from real evidence.
     */
    private final Boolean authObservedInSample;
    /**
     * The real (already-masked) request body this call was captured with, or {@code null}/blank if
     * none was observed (typically GET/DELETE, or a write call never actually captured with a body).
     * Never fabricated: the negative-test engine mutates this real sample rather than inventing a
     * synthetic body from an assumed schema, and honestly reports NOT_APPLICABLE for body-shaped
     * negative cases when this is absent.
     */
    private final String sampleRequestBody;
    private int occurrences;

    private ApiDefinition(String method, String host, String normalizedPath, String samplePath,
                          String sampleQuery, int sampleStatus, Source source, Boolean authObservedInSample,
                          String sampleRequestBody) {
        this.method = method;
        this.host = host;
        this.normalizedPath = normalizedPath;
        this.samplePath = samplePath;
        this.sampleQuery = sampleQuery;
        this.sampleStatus = sampleStatus;
        this.source = source;
        this.authObservedInSample = authObservedInSample;
        this.sampleRequestBody = sampleRequestBody;
        this.occurrences = 1;
    }

    /** Builds a definition straight from a raw request URL, e.g. as captured in a HAR entry. */
    public static ApiDefinition fromRawUrl(String method, String rawUrl, int status, Source source) {
        return fromRawUrl(method, rawUrl, status, source, null, null);
    }

    public static ApiDefinition fromRawUrl(String method, String rawUrl, int status, Source source,
                                           Boolean authObservedInSample, String sampleRequestBody) {
        URI uri = URI.create(rawUrl);
        String host = uri.getHost() == null ? "" : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        return of(method, host, path, uri.getRawQuery(), status, source, authObservedInSample, sampleRequestBody);
    }

    /** Builds a definition from already-split parts, e.g. as captured by {@code ApiMonitor}. */
    public static ApiDefinition of(String method, String host, String path, String query, int status, Source source) {
        return of(method, host, path, query, status, source, null, null);
    }

    public static ApiDefinition of(String method, String host, String path, String query, int status,
                                   Source source, Boolean authObservedInSample) {
        return of(method, host, path, query, status, source, authObservedInSample, null);
    }

    public static ApiDefinition of(String method, String host, String path, String query, int status,
                                   Source source, Boolean authObservedInSample, String sampleRequestBody) {
        String safeMethod = method == null || method.isBlank() ? "UNKNOWN" : method.toUpperCase(Locale.ROOT);
        String safePath = path == null || path.isBlank() ? "/" : path;
        String normalized = ApiNormalizer.normalizePath(safePath);
        return new ApiDefinition(safeMethod, host == null ? "" : host, normalized, safePath, query, status,
                source, authObservedInSample, sampleRequestBody);
    }

    /** Identity used for every comparison/dedup decision: HTTP method + host + normalized path. */
    public String comparisonKey() {
        return method + " " + host + normalizedPath;
    }

    public String method() { return method; }
    public String host() { return host; }
    public String normalizedPath() { return normalizedPath; }
    public String samplePath() { return samplePath; }
    public String sampleQuery() { return sampleQuery; }
    public int sampleStatus() { return sampleStatus; }
    public Source source() { return source; }
    public int occurrences() { return occurrences; }

    /**
     * Whether the real, original capture of this call carried an Authorization header - {@code null}
     * when never observed either way. Never guessed: this only ever reflects real evidence from
     * ApiMonitor's capture or a HAR trace, so a security check can tell "known to be public" apart
     * from "known to require auth" apart from "not observed".
     */
    public Boolean authObservedInSample() { return authObservedInSample; }

    /** The real (masked) request body this call was captured with; {@code null}/blank if none observed. */
    public String sampleRequestBody() { return sampleRequestBody; }

    public boolean hasSampleRequestBody() {
        return sampleRequestBody != null && !sampleRequestBody.isBlank();
    }

    /** Package-visible: only {@link ApiDeduplicator} merges occurrences across repeated entries. */
    void addOccurrence() {
        occurrences++;
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", method);
        map.put("host", host);
        map.put("normalizedPath", normalizedPath);
        map.put("samplePath", samplePath);
        map.put("sampleQuery", sampleQuery == null ? "" : sampleQuery);
        map.put("sampleStatus", sampleStatus);
        map.put("source", source.name());
        map.put("occurrences", occurrences);
        map.put("authObservedInSample", authObservedInSample);
        map.put("hasSampleRequestBody", hasSampleRequestBody());
        return map;
    }
}
