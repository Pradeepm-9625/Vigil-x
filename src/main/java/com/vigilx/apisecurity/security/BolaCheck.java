package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.restassured.response.Response;

import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;
import com.vigilx.config.ConfigReader;

/**
 * API1 - Broken Object Level Authorization.
 *
 * <p><strong>Honest limitation up front</strong>: meaningful BOLA testing means proving this
 * account cannot read/modify/delete a real object that belongs to a <em>different</em> user/tenant.
 * That requires a second, lower-privileged test account and a real object ID known to belong to it -
 * neither is configured in this project (only one admin-role account exists in config). The primary
 * finding for this category is therefore {@code NOT_ASSESSED}, said plainly rather than inferred from
 * a weaker proxy check.
 *
 * <p>What <em>is</em> safely checked, as supporting evidence only, and now across
 * <strong>every</strong> {@code {id}}-shaped endpoint in the final inventory regardless of method (GET,
 * PUT, PATCH, DELETE alike - a random, almost-certainly-nonexistent ID is exactly as safe to send to a
 * DELETE as to a GET, since it should be rejected before reaching any real resource): substituting a
 * syntactically valid but fabricated random UUID into the real captured path. A clean 404/403/400 is
 * expected either way (with or without proper authorization checks) so this does not by itself
 * demonstrate authorization is enforced - it only confirms the endpoint does not trivially leak or act
 * on unrelated data for a made-up ID.
 */
public final class BolaCheck implements SecurityCheck {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API1_BOLA;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();

        findings.add(new SecurityFinding(SecurityHttp.now(), category(),
                "Cross-tenant object access (primary BOLA test)", null, null, Verdict.NOT_ASSESSED,
                Severity.INFO,
                "Not assessed: this requires a second, lower-privileged test account and a real object "
                        + "ID owned by it, neither of which is configured (only one admin-role account exists). "
                        + "Provide a second account to enable a real cross-tenant check.", null));

        ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
        List<ApiDefinition> targets = context.combinedInventory().stream()
                .filter(policy::isEligibleForNegativeTest)
                .filter(definition -> definition.normalizedPath().contains("{id}"))
                .toList();

        long delayMs = longConfig("apisecurity.restassured.delay.ms", 300);
        int consecutiveErrors = 0;
        boolean circuitOpen = false;

        for (ApiDefinition definition : targets) {
            if (circuitOpen) {
                findings.add(new SecurityFinding(SecurityHttp.now(), category(), "Random-ID probe",
                        definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                        "Skipped: circuit breaker open after repeated request errors earlier in this check.", null));
                continue;
            }
            SecurityFinding finding = checkRandomId(context, definition);
            findings.add(finding);
            consecutiveErrors = finding.isRequestError() ? consecutiveErrors + 1 : 0;
            if (consecutiveErrors >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpen = true;
                System.err.println("[BOLA CHECK] Circuit breaker tripped after " + consecutiveErrors
                        + " consecutive request errors - remaining APIs marked SKIPPED.");
            }
            sleep(delayMs);
        }

        return findings;
    }

    private SecurityFinding checkRandomId(SecurityCheckContext context, ApiDefinition definition) {
        String randomizedPath = definition.samplePath().replaceFirst(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                UUID.randomUUID().toString());
        if (randomizedPath.equals(definition.samplePath())) {
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Random-ID probe (supplementary, not a substitute for a real cross-tenant test)",
                    definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                    "Sample path did not contain a UUID-shaped ID segment to substitute.", null);
        }
        String url = context.scheme() + "://" + definition.host() + randomizedPath;
        String body = definition.hasSampleRequestBody() ? definition.sampleRequestBody() : null;
        try {
            Response response = SecurityHttp.request(definition.method(), url, body,
                    body == null ? null : "application/json", Map.of("accept", "application/json",
                            "Authorization", "Bearer " + context.validToken()));
            int status = response.statusCode();
            boolean sane = status == 404 || status == 403 || status == 400;
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Random-ID probe (supplementary, not a substitute for a real cross-tenant test)",
                    definition.method(), definition.normalizedPath(),
                    sane ? Verdict.PASS : Verdict.FAIL, sane ? Severity.INFO : Severity.MEDIUM,
                    sane ? "A random, almost-certainly-nonexistent ID correctly returned " + status + "."
                            : "A random, almost-certainly-nonexistent ID returned " + status
                                    + " - unexpected; worth a closer look, though this does not by itself "
                                    + "prove or disprove real cross-tenant authorization.",
                    "HTTP " + status);
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Random-ID probe", definition.method(), definition.normalizedPath(),
                    Verdict.NOT_ASSESSED, Severity.INFO, "Check could not run: " + exception.getMessage(), null);
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
