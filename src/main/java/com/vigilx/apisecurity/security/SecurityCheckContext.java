package com.vigilx.apisecurity.security;

import java.util.List;

import com.vigilx.apisecurity.inventory.ApiComparisonService.ComparisonResult;
import com.vigilx.apisecurity.inventory.ApiDefinition;

/** Shared, read-only state every {@link SecurityCheck} needs - built once per run. */
public final class SecurityCheckContext {

    private final String scheme;
    private final String authHost;
    private final String validToken;
    private final String username;
    private final String password;
    private final List<ApiDefinition> eligibleGetApis;
    private final List<ApiDefinition> combinedInventory;
    private final ComparisonResult comparisonResult;

    public SecurityCheckContext(String scheme, String authHost, String validToken, String username,
                                String password, List<ApiDefinition> eligibleGetApis,
                                List<ApiDefinition> combinedInventory, ComparisonResult comparisonResult) {
        this.scheme = scheme;
        this.authHost = authHost;
        this.validToken = validToken;
        this.username = username;
        this.password = password;
        this.eligibleGetApis = eligibleGetApis;
        this.combinedInventory = combinedInventory;
        this.comparisonResult = comparisonResult;
    }

    public String scheme() { return scheme; }
    public String authHost() { return authHost; }
    public String validToken() { return validToken; }
    public String username() { return username; }
    public String password() { return password; }
    public List<ApiDefinition> eligibleGetApis() { return eligibleGetApis; }
    public List<ApiDefinition> combinedInventory() { return combinedInventory; }
    public ComparisonResult comparisonResult() { return comparisonResult; }

    public String urlOf(ApiDefinition definition) {
        String query = definition.sampleQuery();
        return scheme + "://" + definition.host() + definition.samplePath()
                + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
