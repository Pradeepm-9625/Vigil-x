package com.vigilx.apisecurity.zap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.restassured.RestAssured;
import io.restassured.response.Response;

/**
 * Thin wrapper over OWASP ZAP's own JSON API (no extra ZAP client library dependency - ZAP's API is
 * plain HTTP/JSON, and REST Assured already handles that). Every call is scoped to one local ZAP
 * daemon instance addressed by {@code zapBaseUrl}; the API key travels as a query parameter exactly
 * as ZAP's own documentation shows.
 */
public final class ZapClient {

    private final String zapBaseUrl;
    private final String apiKey;

    public ZapClient(String address, int port, String apiKey) {
        this.zapBaseUrl = "http://" + address + ":" + port;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    /** True when a ZAP daemon actually answers on this address/port. Never throws. */
    public boolean isRunning() {
        try {
            Response response = get("/JSON/core/view/version/", Map.of());
            return response.statusCode() == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    public String version() {
        return get("/JSON/core/view/version/", Map.of()).jsonPath().getString("version");
    }

    /**
     * Adds a Replacer rule so every request ZAP itself sends (via {@link #accessUrl}, active scan,
     * etc.) carries this header - the mechanism used to inject the Bearer token, since this app's
     * Bearer-JWT auth does not fit ZAP's built-in cookie/form authentication methods.
     */
    public void setHeaderReplacerRule(String headerName, String headerValue) {
        get("/JSON/replacer/action/addRule/", Map.of(
                "description", "vigilx-auth-header",
                "enabled", "true",
                "matchType", "REQ_HEADER",
                "matchRegex", "false",
                "matchString", headerName,
                "replacement", headerValue));
    }

    /** Has ZAP itself fetch a URL - triggers passive scanning of the response automatically. */
    public void accessUrl(String url) {
        get("/JSON/core/action/accessUrl/", Map.of("url", url, "followRedirects", "true"));
    }

    /** Messages still queued for the passive scanner. */
    public int recordsToScan() {
        try {
            return Integer.parseInt(get("/JSON/pscan/view/recordsToScan/", Map.of()).jsonPath().getString("recordsToScan"));
        } catch (Exception exception) {
            return 0;
        }
    }

    /** Starts an active scan against one specific URL (not a full-site spider+scan). Returns the scan id. */
    public String activeScan(String url) {
        return get("/JSON/ascan/action/scan/", Map.of(
                "url", url, "recurse", "false", "inScopeOnly", "false")).jsonPath().getString("scan");
    }

    /** 0-100, or -1 if the scan id is unknown. */
    public int activeScanProgress(String scanId) {
        try {
            return Integer.parseInt(get("/JSON/ascan/view/status/", Map.of("scanId", scanId))
                    .jsonPath().getString("status"));
        } catch (Exception exception) {
            return -1;
        }
    }

    /** Every alert ZAP has raised so far for URLs under this base URL. */
    public List<Map<String, Object>> alerts(String baseUrl) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        Response response = get("/JSON/core/view/alerts/", Map.of("baseurl", baseUrl, "start", "0", "count", "5000"));
        List<Map<String, Object>> raw = response.jsonPath().getList("alerts");
        if (raw != null) {
            alerts.addAll(raw);
        }
        return alerts;
    }

    /** Best-effort clean shutdown of the ZAP daemon this client is talking to. */
    public void shutdown() {
        try {
            get("/JSON/core/action/shutdown/", Map.of());
        } catch (Exception ignored) {
            // The daemon closing its own connection before replying is expected.
        }
    }

    private Response get(String path, Map<String, String> params) {
        var request = RestAssured.given().queryParam("apikey", apiKey);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            request = request.queryParam(entry.getKey(), entry.getValue());
        }
        return request.when().get(zapBaseUrl + path);
    }
}
