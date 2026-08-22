package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.restassured.response.Response;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.SecretMasker;

/**
 * API3 - Broken Object Property Level Authorization (excessive data exposure half).
 *
 * <p>Real check performed: a sample of real, eligible GET responses is inspected - in memory, for
 * this check only - for property names that should never appear in a normal API response
 * ({@code password}, {@code passwordHash}, raw {@code accessToken}/{@code refreshToken}, etc.). Any
 * evidence kept in the report is masked via the same {@link SecretMasker} the rest of the framework
 * uses; the raw response is never written to disk.
 *
 * <p>Not performed: mass-assignment testing (sending extra, unexpected properties on a write call to
 * see if the API accepts them) - that requires a POST/PUT/PATCH call, which the default GET-only
 * execution policy does not permit. Reported as {@code NOT_ASSESSED}, not skipped silently.
 */
public final class SensitiveDataExposureCheck implements SecurityCheck {

    private static final Set<String> SUSPICIOUS_KEYS = Set.of(
            "password", "passwordhash", "password_hash", "secret", "privatekey", "private_key",
            "accesstoken", "access_token", "refreshtoken", "refresh_token");

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API3_BROKEN_PROPERTY_AUTHORIZATION;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();
        int sampleSize = intConfig("apisecurity.security.exposure.sample.size", 15);
        List<ApiDefinition> sample = context.eligibleGetApis().stream().limit(sampleSize).toList();

        int checked = 0;
        for (ApiDefinition definition : sample) {
            ProbeOutcome outcome = checkOne(context, definition);
            if (outcome.attempted) {
                checked++;
            }
            if (outcome.finding != null) {
                findings.add(outcome.finding);
            }
        }

        if (findings.stream().noneMatch(f -> f.verdict() == Verdict.FAIL)) {
            boolean anyChecked = checked > 0;
            findings.add(new SecurityFinding(SecurityHttp.now(), category(),
                    "No sensitive property names found in sampled GET responses", "GET", "(" + checked + " endpoints)",
                    anyChecked ? Verdict.PASS : Verdict.NOT_ASSESSED, Severity.INFO,
                    anyChecked
                            ? "Scanned " + checked + " real GET response bodies for sensitive property names; "
                                    + "none were found. This covers only the sampled endpoints, not the full inventory."
                            : "Could not scan any GET response body (all " + sample.size() + " sampled "
                                    + "requests errored) - see individual endpoint results for details.",
                    null));
        }

        findings.add(new SecurityFinding(SecurityHttp.now(), category(), "Mass assignment on write endpoints",
                "POST/PUT/PATCH", "(all write endpoints)", Verdict.NOT_ASSESSED, Severity.INFO,
                "Not auto-tested: this requires sending a real write request with extra properties, which "
                        + "the default GET-only execution policy does not permit against a live environment.",
                null));

        return findings;
    }

    /** {@code attempted} is true whenever the HTTP call itself succeeded, regardless of what it found. */
    private record ProbeOutcome(boolean attempted, SecurityFinding finding) { }

    private ProbeOutcome checkOne(SecurityCheckContext context, ApiDefinition definition) {
        try {
            Response response = SecurityHttp.get(context.urlOf(definition), Map.of("accept", "application/json",
                    "Authorization", "Bearer " + context.validToken()));
            String body = response.asString();
            if (body == null || body.isBlank()) {
                return new ProbeOutcome(true, null);
            }
            String foundKey = findSuspiciousKey(body);
            if (foundKey == null) {
                return new ProbeOutcome(true, null);
            }
            return new ProbeOutcome(true, new SecurityFinding(SecurityHttp.now(), category(),
                    "Sensitive property exposed in GET response", definition.method(),
                    definition.normalizedPath(), Verdict.FAIL, Severity.HIGH,
                    "Response body contains a \"" + foundKey + "\" property, which should not be returned "
                            + "by a read endpoint.",
                    SecretMasker.maskBody(body, 500)));
        } catch (Exception exception) {
            return new ProbeOutcome(false, null);
        }
    }

    /** Naive but effective: looks for a JSON key matching a suspicious name, case-insensitively. */
    private String findSuspiciousKey(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        for (String key : SUSPICIOUS_KEYS) {
            if (lower.contains("\"" + key + "\"")) {
                return key;
            }
        }
        return null;
    }

    private static int intConfig(String key, int fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }
}
