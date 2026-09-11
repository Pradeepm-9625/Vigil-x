package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.restassured.response.Response;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;

/**
 * API7 - Server Side Request Forgery.
 *
 * <p>This application has a real, legitimate feature that is a classic SSRF surface by shape:
 * {@code GET /devices/check-availability?ipAddress=...&port=...} makes the backend probe a
 * caller-supplied host/port to see if a camera is reachable there. That is the feature working as
 * designed for a real device IP - the security question is only what happens when the supplied
 * address points somewhere it should not: loopback, or the cloud-metadata address
 * (169.254.169.254) used by AWS/GCP/Azure instance metadata endpoints.
 *
 * <p>This check only ever issues plain GETs with those two addresses - it never attempts to reach an
 * attacker-controlled external listener, and reports what the backend's behavior implies rather than
 * asserting a vulnerability from a black-box response alone.
 */
public final class SsrfProbeCheck implements SecurityCheck {

    private static final String CHECK_AVAILABILITY_PATH = "/devices/check-availability";

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API7_SSRF;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();

        ApiDefinition target = context.eligibleGetApis().stream()
                .filter(definition -> definition.normalizedPath().equals(CHECK_AVAILABILITY_PATH))
                .findFirst()
                .orElse(null);

        if (target == null) {
            findings.add(new SecurityFinding(SecurityHttp.now(), category(),
                    "Internal-address probe via check-availability", "GET", CHECK_AVAILABILITY_PATH,
                    Verdict.NOT_ASSESSED, Severity.INFO,
                    "No SSRF-shaped endpoint (a caller-supplied host/port the backend connects to) "
                            + "was found in this run's eligible inventory.", null));
            return findings;
        }

        findings.add(probe(context, target, "127.0.0.1", "22", "loopback address"));
        findings.add(probe(context, target, "169.254.169.254", "80", "cloud instance metadata address"));

        return findings;
    }

    private SecurityFinding probe(SecurityCheckContext context, ApiDefinition target, String ip, String port,
                                  String addressKind) {
        String url = context.scheme() + "://" + target.host() + CHECK_AVAILABILITY_PATH
                + "?ipAddress=" + ip + "&port=" + port;
        try {
            Response response = SecurityHttp.get(url, Map.of("accept", "application/json",
                    "Authorization", "Bearer " + context.validToken()));
            int status = response.statusCode();
            String body = response.asString() == null ? "" : response.asString();
            boolean leaksDetail = body.length() > 200 || containsBannerLikeContent(body);

            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Backend behavior when probing a " + addressKind, target.method(),
                    CHECK_AVAILABILITY_PATH,
                    leaksDetail ? Verdict.FAIL : Verdict.PASS,
                    leaksDetail ? Severity.HIGH : Severity.INFO,
                    leaksDetail
                            ? "Probing " + ip + ":" + port + " (" + addressKind + ") returned a detailed "
                                    + "response (HTTP " + status + ", " + body.length() + " bytes) that may "
                                    + "expose internal service information."
                            : "Probing " + ip + ":" + port + " (" + addressKind + ") returned a short, "
                                    + "non-descriptive response (HTTP " + status + ") consistent with a "
                                    + "simple reachability check rather than a data leak.",
                    "HTTP " + status);
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Backend behavior when probing a " + addressKind, target.method(), CHECK_AVAILABILITY_PATH,
                    Verdict.NOT_ASSESSED, Severity.INFO, "Check could not run: " + exception.getMessage(), null);
        }
    }

    private boolean containsBannerLikeContent(String body) {
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("server:") || lower.contains("ssh-") || lower.contains("apache")
                || lower.contains("nginx") || lower.contains("ami-id") || lower.contains("instance-id");
    }
}
