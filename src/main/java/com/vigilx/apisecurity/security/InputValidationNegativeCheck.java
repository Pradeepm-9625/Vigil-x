package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.response.Response;

import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;
import com.vigilx.config.ConfigReader;

/**
 * API8 - Security Misconfiguration (request body input validation, injection-style input included).
 *
 * <p>Every case here mutates the API's own <strong>real captured request body</strong> - never a
 * synthetic body invented from an assumed schema - so this is only applicable to POST/PUT/PATCH APIs
 * where {@link ApiDefinition#hasSampleRequestBody()} is true; everything else (GET/DELETE, or a write
 * call whose body was never actually observed) is honestly {@code NOT_APPLICABLE}, not silently
 * skipped. Cases:
 * <ul>
 *   <li>Empty body {@code {}}</li>
 *   <li>Malformed JSON (the real body, truncated)</li>
 *   <li>Oversized body (the real body plus a large padding field)</li>
 *   <li>Wrong Content-Type (the real body sent as {@code text/plain})</li>
 *   <li>One real string field mutated to an XSS-shaped and a SQLi-shaped payload</li>
 * </ul>
 *
 * <p>Every request keeps the real valid auth token: this check is isolated to input validation, not
 * authentication. A well-behaved API is expected to reject all of these before ever reaching real
 * business/mutation logic - see {@link ApiExecutionPolicy#isEligibleForNegativeTest} for why that
 * makes this safe to run against write endpoints.
 */
public final class InputValidationNegativeCheck implements SecurityCheck {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String XSS_PAYLOAD = "<script>vigilx_probe_alert(1)</script>";
    private static final String SQLI_PAYLOAD = "' OR '1'='1' --";
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API8_SECURITY_MISCONFIGURATION;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();
        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        List<ApiDefinition> writeApis = context.combinedInventory().stream()
                .filter(policy::isEligibleForNegativeTest)
                .filter(definition -> !"GET".equals(definition.method()) && !"DELETE".equals(definition.method()))
                .toList();

        long delayMs = longConfig("apisecurity.restassured.delay.ms", 300);
        int consecutiveErrors = 0;
        boolean circuitOpen = false;

        for (ApiDefinition definition : writeApis) {
            if (!definition.hasSampleRequestBody()) {
                findings.add(notApplicable(definition, "no real request body sample available to mutate"));
                continue;
            }
            if (circuitOpen) {
                findings.add(skipped(definition));
                continue;
            }

            List<SecurityFinding> caseFindings = List.of(
                    checkEmptyBody(context, definition),
                    checkMalformedJson(context, definition),
                    checkOversizedBody(context, definition),
                    checkWrongContentType(context, definition),
                    checkFieldMutation(context, definition, "XSS-shaped", XSS_PAYLOAD),
                    checkFieldMutation(context, definition, "SQLi-shaped", SQLI_PAYLOAD));
            findings.addAll(caseFindings);

            long errors = caseFindings.stream().filter(SecurityFinding::isRequestError).count();
            consecutiveErrors = errors == caseFindings.size() ? consecutiveErrors + 1 : 0;
            if (consecutiveErrors >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpen = true;
                System.err.println("[INPUT VALIDATION CHECK] Circuit breaker tripped after " + consecutiveErrors
                        + " consecutive fully-errored APIs - remaining APIs marked SKIPPED.");
            }
            sleep(delayMs);
        }
        return findings;
    }

    private SecurityFinding checkEmptyBody(SecurityCheckContext context, ApiDefinition definition) {
        return send(context, definition, "Empty body", "{}", "application/json", this::expectRejectionOrClamp);
    }

    private SecurityFinding checkMalformedJson(SecurityCheckContext context, ApiDefinition definition) {
        String real = definition.sampleRequestBody();
        String malformed = real.length() > 2 ? real.substring(0, real.length() - 2) : "{";
        return send(context, definition, "Malformed JSON", malformed, "application/json", this::expectRejectionOrClamp);
    }

    private SecurityFinding checkOversizedBody(SecurityCheckContext context, ApiDefinition definition) {
        try {
            JsonNode node = JSON.readTree(definition.sampleRequestBody());
            if (node.isObject()) {
                ((ObjectNode) node).put("vigilxOversizedProbeField", "X".repeat(200_000));
                return send(context, definition, "Oversized body", JSON.writeValueAsString(node),
                        "application/json", this::expectRejectionOrClamp);
            }
        } catch (Exception ignored) {
            // Falls through to the plain-text fallback below.
        }
        String oversized = definition.sampleRequestBody() + " ".repeat(200_000);
        return send(context, definition, "Oversized body", oversized, "application/json", this::expectRejectionOrClamp);
    }

    private SecurityFinding checkWrongContentType(SecurityCheckContext context, ApiDefinition definition) {
        return send(context, definition, "Wrong Content-Type", definition.sampleRequestBody(), "text/plain",
                this::expectRejectionOrClamp);
    }

    private SecurityFinding checkFieldMutation(SecurityCheckContext context, ApiDefinition definition,
                                               String payloadKind, String payload) {
        String mutated = mutateFirstStringField(definition.sampleRequestBody(), payload);
        if (mutated == null) {
            return notApplicableCase(definition, payloadKind + " field mutation",
                    "the real captured body has no top-level string field to mutate");
        }
        return send(context, definition, payloadKind + " payload in a request field", mutated, "application/json",
                (status, body) -> {
                    if (status >= 500) {
                        return Verdict.FAIL;
                    }
                    if (body != null && body.contains(payload)) {
                        return Verdict.FAIL;
                    }
                    return Verdict.PASS;
                });
    }

    /** Shared send+judge plumbing so every case above is one line of actual HTTP logic. */
    private SecurityFinding send(SecurityCheckContext context, ApiDefinition definition, String caseName,
                                 String body, String contentType, java.util.function.BiFunction<Integer, String, Verdict> judge) {
        String url = context.urlOf(definition);
        try {
            Response response = SecurityHttp.request(definition.method(), url, body, contentType, Map.of(
                    "accept", "application/json", "Authorization", "Bearer " + context.validToken()));
            int status = response.statusCode();
            String responseBody = safeBody(response);
            Verdict verdict = judge.apply(status, responseBody);
            Severity severity = verdict == Verdict.FAIL ? (status >= 500 ? Severity.HIGH : Severity.MEDIUM) : Severity.INFO;
            String summary = verdict == Verdict.PASS
                    ? caseName + " was handled safely (HTTP " + status + ")."
                    : caseName + " was not safely handled (HTTP " + status + ") - expected a clean 4xx rejection.";
            return new SecurityFinding(SecurityHttp.now(), category(), caseName, definition.method(),
                    definition.normalizedPath(), verdict, severity, summary, "HTTP " + status);
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(), caseName, definition.method(),
                    definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                    "Check could not run: " + exception.getMessage(), null);
        }
    }

    /** Empty/malformed/oversized/wrong-Content-Type all share the same judgment: reject cleanly, never 2xx/500. */
    private Verdict expectRejectionOrClamp(int status, String body) {
        if (status >= 500) {
            return Verdict.FAIL;
        }
        if (status >= 200 && status < 300) {
            return Verdict.FAIL;
        }
        return Verdict.PASS;
    }

    private static String mutateFirstStringField(String jsonBody, String replacement) {
        try {
            JsonNode node = JSON.readTree(jsonBody);
            if (!node.isObject()) {
                return null;
            }
            ObjectNode object = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().isTextual()) {
                    object.put(field.getKey(), replacement);
                    return JSON.writeValueAsString(object);
                }
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String safeBody(Response response) {
        try {
            return response.asString();
        } catch (Exception exception) {
            return null;
        }
    }

    private SecurityFinding notApplicable(ApiDefinition definition, String reason) {
        return new SecurityFinding(SecurityHttp.now(), category(), "Input validation (all cases)",
                definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                "NEGATIVE_TESTS = NOT_APPLICABLE: " + reason, null);
    }

    private SecurityFinding notApplicableCase(ApiDefinition definition, String caseName, String reason) {
        return new SecurityFinding(SecurityHttp.now(), category(), caseName, definition.method(),
                definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO, "Not applicable: " + reason, null);
    }

    private SecurityFinding skipped(ApiDefinition definition) {
        return new SecurityFinding(SecurityHttp.now(), category(), "Input validation (all cases)",
                definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                "Skipped: circuit breaker open after repeated request errors earlier in this check.", null);
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
