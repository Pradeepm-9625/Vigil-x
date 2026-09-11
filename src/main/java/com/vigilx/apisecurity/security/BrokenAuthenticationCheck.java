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
 * API2 - Broken Authentication.
 *
 * <p>Real, safe checks performed against <strong>every</strong> negative-eligible API in the final
 * inventory - GET, POST, PUT, PATCH and DELETE alike, not a small GET-only sample. A missing/invalid
 * token is expected to be rejected before an endpoint ever reaches its real logic, so this is safe to
 * send at a write endpoint even though a real, successful write call is not (see
 * {@link ApiExecutionPolicy#isEligibleForNegativeTest}):
 * <ul>
 *   <li>Missing token - call the real endpoint (its real captured method, path and body if any) with
 *       no Authorization header; a 2xx is a genuine authentication bypass.</li>
 *   <li>Invalid/tampered token - the same call with a syntactically-token-shaped but bogus bearer value.</li>
 *   <li>Invalid credentials - exactly <strong>one</strong> {@code /auth/login} attempt with a wrong
 *       password, run once for the whole check, not once per API: the endpoint is itself rate-limited
 *       (observed 5/window), and this shares that limit with every legitimate login the framework does.</li>
 * </ul>
 *
 * <p>Not performed: a true "expired token" check, which would need either waiting for the real ~4-day
 * expiry or possessing the signing key to forge one - reported as {@code NOT_ASSESSED}, not faked.
 */
public final class BrokenAuthenticationCheck implements SecurityCheck {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API2_BROKEN_AUTHENTICATION;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();
        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        List<ApiDefinition> targets = context.combinedInventory().stream()
                .filter(policy::isEligibleForNegativeTest)
                .toList();

        long delayMs = longConfig("apisecurity.restassured.delay.ms", 300);
        int consecutiveErrors = 0;
        boolean circuitOpen = false;

        for (ApiDefinition definition : targets) {
            if (circuitOpen) {
                findings.add(skipped("Missing token rejected", definition));
                findings.add(skipped("Invalid/tampered token rejected", definition));
                continue;
            }

            SecurityFinding missing = checkMissingToken(context, definition);
            SecurityFinding invalid = checkInvalidToken(context, definition);
            findings.add(missing);
            findings.add(invalid);

            boolean errored = missing.isRequestError() && invalid.isRequestError();
            consecutiveErrors = errored ? consecutiveErrors + 1 : 0;
            if (consecutiveErrors >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpen = true;
                System.err.println("[BROKEN AUTH CHECK] Circuit breaker tripped after " + consecutiveErrors
                        + " consecutive request errors - remaining APIs marked SKIPPED.");
            }
            sleep(delayMs);
        }

        findings.add(checkInvalidCredentials(context));

        findings.add(new SecurityFinding(SecurityHttp.now(), category(), "Expired token", "POST",
                "/auth/login", Verdict.NOT_ASSESSED, Severity.INFO,
                "Not testable without either waiting out the real ~4-day token expiry or possessing "
                        + "the JWT signing key to forge an expired-but-validly-signed token. No fabricated result recorded.",
                null));

        return findings;
    }

    private SecurityFinding checkMissingToken(SecurityCheckContext context, ApiDefinition definition) {
        if (Boolean.FALSE.equals(definition.authObservedInSample())) {
            return knownPublicFinding("Missing token rejected", definition);
        }
        try {
            Response response = replay(context, definition, Map.of("accept", "application/json"));
            int status = response.statusCode();
            boolean rejected = status == 401 || status == 403;
            if (!rejected && inconclusiveWithoutBody(definition, status)) {
                return inconclusiveFinding("Missing token rejected", definition, status);
            }
            return new SecurityFinding(SecurityHttp.now(), category(), "Missing token rejected",
                    definition.method(), definition.normalizedPath(),
                    rejected ? Verdict.PASS : Verdict.FAIL,
                    rejected ? Severity.INFO : Severity.CRITICAL,
                    rejected ? "Request without an Authorization header was correctly rejected (" + status + ")."
                            : "Request without an Authorization header returned " + status
                                    + " instead of 401/403 - possible authentication bypass.",
                    "HTTP " + status);
        } catch (Exception exception) {
            return errorFinding("Missing token rejected", definition, exception);
        }
    }

    private SecurityFinding checkInvalidToken(SecurityCheckContext context, ApiDefinition definition) {
        if (Boolean.FALSE.equals(definition.authObservedInSample())) {
            return knownPublicFinding("Invalid/tampered token rejected", definition);
        }
        try {
            Response response = replay(context, definition, Map.of(
                    "accept", "application/json",
                    "Authorization", "Bearer invalid.tampered.token-" + System.nanoTime()));
            int status = response.statusCode();
            boolean rejected = status == 401 || status == 403;
            if (!rejected && inconclusiveWithoutBody(definition, status)) {
                return inconclusiveFinding("Invalid/tampered token rejected", definition, status);
            }
            return new SecurityFinding(SecurityHttp.now(), category(), "Invalid/tampered token rejected",
                    definition.method(), definition.normalizedPath(),
                    rejected ? Verdict.PASS : Verdict.FAIL,
                    rejected ? Severity.INFO : Severity.CRITICAL,
                    rejected ? "Request with a bogus bearer token was correctly rejected (" + status + ")."
                            : "Request with a bogus bearer token returned " + status
                                    + " instead of 401/403 - possible token validation weakness.",
                    "HTTP " + status);
        } catch (Exception exception) {
            return errorFinding("Invalid/tampered token rejected", definition, exception);
        }
    }

    /**
     * Replays the API's own real method/path/body (if any) with the given headers - never a
     * synthetic body invented from an assumed schema. Bodyless methods (GET/DELETE, or a write call
     * whose body was never actually captured) are sent with no body, which is exactly what a
     * missing/invalid-token probe needs: the point is the auth header, not the payload.
     */
    private Response replay(SecurityCheckContext context, ApiDefinition definition, Map<String, String> headers) {
        String url = context.urlOf(definition);
        String body = definition.hasSampleRequestBody() ? definition.sampleRequestBody() : null;
        return SecurityHttp.request(definition.method(), url, body, body == null ? null : "application/json", headers);
    }

    /**
     * The endpoint's own real, original capture shows it was called with no Authorization header at
     * all (e.g. a pre-login bootstrap/status check) - so a 200 without a token here is the endpoint
     * working as designed, not a bypass. Recorded as evidence-backed PASS, not asserted as a finding
     * from a blind assumption that every endpoint requires auth.
     */
    private SecurityFinding knownPublicFinding(String checkName, ApiDefinition definition) {
        return new SecurityFinding(SecurityHttp.now(), category(), checkName, definition.method(),
                definition.normalizedPath(), Verdict.PASS, Severity.INFO,
                "Not flagged: this endpoint's own real captured traffic shows it is called without an "
                        + "Authorization header during normal (legitimate, pre-login) use, so it is treated "
                        + "as an intentionally public endpoint rather than a missing-auth vulnerability.",
                "authObservedInSample=false");
    }

    /**
     * True when a non-2xx, non-401/403 status (typically 400/422) cannot be trusted as evidence
     * either way: this method normally carries a body, no real captured body sample exists to
     * replay, and the request was therefore sent bodyless - a route that validates its payload before
     * (or regardless of) authentication would produce exactly this response with a valid token too,
     * so it says nothing about whether auth is actually enforced.
     */
    private boolean inconclusiveWithoutBody(ApiDefinition definition, int status) {
        boolean bodyBearingMethod = "POST".equals(definition.method()) || "PUT".equals(definition.method())
                || "PATCH".equals(definition.method());
        boolean success = status >= 200 && status < 300;
        return bodyBearingMethod && !definition.hasSampleRequestBody() && !success;
    }

    private SecurityFinding inconclusiveFinding(String checkName, ApiDefinition definition, int status) {
        return new SecurityFinding(SecurityHttp.now(), category(), checkName, definition.method(),
                definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                "Inconclusive: returned " + status + ", but no real request body sample exists for this "
                        + definition.method() + " endpoint, so this call was sent with no body. A route that "
                        + "validates its payload before (or regardless of) authentication would return the "
                        + "same status even with a valid token, so this cannot be trusted as evidence either "
                        + "way - not counted as a pass or a finding.", "HTTP " + status);
    }

    private SecurityFinding checkInvalidCredentials(SecurityCheckContext context) {
        String url = context.scheme() + "://" + context.authHost() + "/auth/login";
        try {
            Response response = SecurityHttp.postJson(url,
                    Map.of("email", context.username(), "password", "definitely-wrong-" + System.nanoTime()),
                    Map.of("accept", "application/json", "instance", "web"));
            int status = response.statusCode();
            boolean rejected = status == 401 || status == 400;
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Invalid credentials rejected (single attempt only - endpoint is rate-limited)",
                    "POST", "/auth/login",
                    rejected ? Verdict.PASS : Verdict.FAIL,
                    rejected ? Severity.INFO : Severity.CRITICAL,
                    rejected ? "Login with a wrong password was correctly rejected (" + status + ")."
                            : "Login with a wrong password returned " + status + " instead of 400/401.",
                    "HTTP " + status);
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Invalid credentials rejected", "POST", "/auth/login", Verdict.NOT_ASSESSED,
                    Severity.INFO, "Check could not run: " + exception.getMessage(), null);
        }
    }

    private SecurityFinding errorFinding(String checkName, ApiDefinition definition, Exception exception) {
        return new SecurityFinding(SecurityHttp.now(), category(), checkName, definition.method(),
                definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                "Check could not run: " + exception.getMessage(), null);
    }

    private SecurityFinding skipped(String checkName, ApiDefinition definition) {
        return new SecurityFinding(SecurityHttp.now(), category(), checkName, definition.method(),
                definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
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
