package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.restassured.response.Response;

import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;
import com.vigilx.config.ConfigReader;

/**
 * API8 - Security Misconfiguration (unsupported-method handling).
 *
 * <p>For every write API (POST/PUT/PATCH/DELETE) in the final inventory, probes the same real path
 * with <strong>GET only</strong> - deliberately never a method that could itself be destructive if
 * wrongly accepted. Sending an unexpected DELETE/PUT/PATCH to a route to see if it is wrongly accepted
 * is exactly the failure mode this check exists to catch, so doing that itself would be reckless; GET
 * can reveal the same routing/method-handling weakness (an endpoint responding successfully to a
 * method it was never meant to accept) without ever being able to mutate anything.
 *
 * <p>GET endpoints are therefore not probed here at all - there is no safe direction left to test them
 * in - and are recorded {@code NOT_APPLICABLE} with that reason, not silently skipped.
 */
public final class MethodNegativeCheck implements SecurityCheck {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API8_SECURITY_MISCONFIGURATION;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();
        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        List<ApiDefinition> all = context.combinedInventory().stream()
                .filter(policy::isEligibleForNegativeTest)
                .toList();

        long delayMs = longConfig("apisecurity.restassured.delay.ms", 300);
        int consecutiveErrors = 0;
        boolean circuitOpen = false;

        for (ApiDefinition definition : all) {
            if ("GET".equals(definition.method())) {
                findings.add(new SecurityFinding(SecurityHttp.now(), category(),
                        "Unsupported-method probe", definition.method(), definition.normalizedPath(),
                        Verdict.NOT_ASSESSED, Severity.INFO,
                        "Not applicable: the only safe probe direction (GET) is what this endpoint already "
                                + "is; probing a GET endpoint with a write method risks a real mutation if it "
                                + "is wrongly accepted, which this framework will not risk.", null));
                continue;
            }
            if (circuitOpen) {
                findings.add(new SecurityFinding(SecurityHttp.now(), category(), "Unsupported-method probe",
                        definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                        "Skipped: circuit breaker open after repeated request errors earlier in this check.", null));
                continue;
            }

            SecurityFinding finding = checkAlternateMethod(context, definition);
            findings.add(finding);
            consecutiveErrors = finding.isRequestError() ? consecutiveErrors + 1 : 0;
            if (consecutiveErrors >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpen = true;
                System.err.println("[METHOD NEGATIVE CHECK] Circuit breaker tripped after " + consecutiveErrors
                        + " consecutive request errors - remaining APIs marked SKIPPED.");
            }
            sleep(delayMs);
        }
        return findings;
    }

    private SecurityFinding checkAlternateMethod(SecurityCheckContext context, ApiDefinition definition) {
        // A documented GET on the exact same path is completely normal REST design (e.g.
        // POST /devices to create, GET /devices to list) - not a method-confusion finding at all.
        boolean hasDocumentedGetSibling = context.combinedInventory().stream()
                .anyMatch(other -> "GET".equals(other.method())
                        && other.normalizedPath().equals(definition.normalizedPath()));
        if (hasDocumentedGetSibling) {
            return new SecurityFinding(SecurityHttp.now(), category(), "Unsupported-method probe",
                    definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                    "Not applicable: GET is already a separately documented operation on this exact path "
                            + "(a normal REST pattern - e.g. list vs. create) - not a method-confusion finding.", null);
        }

        String url = context.urlOf(definition);
        try {
            Response response = SecurityHttp.request("GET", url, null, null, Map.of(
                    "accept", "application/json", "Authorization", "Bearer " + context.validToken()));
            int status = response.statusCode();
            if (status >= 500) {
                return new SecurityFinding(SecurityHttp.now(), category(), "Unsupported-method probe (GET against a "
                        + definition.method() + " endpoint)", definition.method(), definition.normalizedPath(),
                        Verdict.FAIL, Severity.MEDIUM,
                        "GET against this " + definition.method() + " route caused a server error (" + status
                                + ") - a robustness/error-handling issue regardless of whether GET is meant to work here.",
                        "HTTP " + status);
            }
            boolean rejected = status == 404 || status == 405 || status == 400 || status == 401 || status == 403;
            if (rejected) {
                return new SecurityFinding(SecurityHttp.now(), category(), "Unsupported-method probe (GET against a "
                        + definition.method() + " endpoint)", definition.method(), definition.normalizedPath(),
                        Verdict.PASS, Severity.INFO,
                        "GET against this " + definition.method() + "-only route was rejected (" + status + ").",
                        "HTTP " + status);
            }
            // A 2xx here is NOT reliable evidence of a problem: this endpoint may simply have a real GET
            // sibling this framework never happened to observe (neither ApiMonitor nor the one HAR
            // recording session is a complete OpenAPI spec). Reliably testing this needs an OPTIONS/Allow
            // header check, which is not implemented - reported honestly as inconclusive, not fabricated.
            return new SecurityFinding(SecurityHttp.now(), category(), "Unsupported-method probe (GET against a "
                    + definition.method() + " endpoint)", definition.method(), definition.normalizedPath(),
                    Verdict.NOT_ASSESSED, Severity.INFO,
                    "Inconclusive: GET returned " + status + ". This is not reliable evidence of a method-handling "
                            + "problem - the path may have an undocumented-to-this-framework GET operation. A "
                            + "trustworthy version of this check would need an OPTIONS/Allow-header probe, which "
                            + "is not implemented.", "HTTP " + status);
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(), "Unsupported-method probe",
                    definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                    "Check could not run: " + exception.getMessage(), null);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static long longConfig(String key, long fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }
}
