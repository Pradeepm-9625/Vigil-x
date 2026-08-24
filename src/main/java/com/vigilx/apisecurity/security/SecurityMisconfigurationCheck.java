package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.restassured.response.Response;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;

/**
 * API8 - Security Misconfiguration.
 *
 * <p>Real, safe checks performed, all read-only GET calls:
 * <ul>
 *   <li>Presence of baseline security response headers (HSTS, X-Content-Type-Options, X-Frame-Options)
 *       on a real authenticated response.</li>
 *   <li>A request to a clearly nonexistent path, checked for a leaked stack trace / framework
 *       internals in the error body.</li>
 *   <li>Reflected-input probes: real GET endpoints that accept a {@code search=} query parameter are
 *       called once with a benign XSS-shaped marker and once with a benign SQLi-shaped marker,
 *       checking the response is neither a 500 nor an unescaped reflection of the payload. This is
 *       the safe, non-destructive form of the brief's "injection-style payload" checks - GET only,
 *       no data is written.</li>
 * </ul>
 */
public final class SecurityMisconfigurationCheck implements SecurityCheck {

    private static final List<String> EXPECTED_HEADERS = List.of(
            "strict-transport-security", "x-content-type-options", "x-frame-options");

    private static final String XSS_MARKER = "<script>vigilx_probe_alert(1)</script>";
    private static final String SQLI_MARKER = "' OR '1'='1' --";

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API8_SECURITY_MISCONFIGURATION;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();

        context.eligibleGetApis().stream().findFirst()
                .ifPresent(definition -> findings.add(checkSecurityHeaders(context, definition)));

        findings.add(checkVerboseErrors(context));

        for (ApiDefinition definition : context.eligibleGetApis()) {
            if (hasSearchParam(definition)) {
                findings.add(checkReflection(context, definition, "search", XSS_MARKER, "XSS-shaped"));
                findings.add(checkReflection(context, definition, "search", SQLI_MARKER, "SQLi-shaped"));
                break; // one representative endpoint is enough for a non-destructive probe
            }
        }

        return findings;
    }

    private boolean hasSearchParam(ApiDefinition definition) {
        String query = definition.sampleQuery();
        return query != null && query.toLowerCase(Locale.ROOT).contains("search=");
    }

    private SecurityFinding checkSecurityHeaders(SecurityCheckContext context, ApiDefinition definition) {
        try {
            Response response = SecurityHttp.get(context.urlOf(definition), Map.of(
                    "accept", "application/json", "Authorization", "Bearer " + context.validToken()));
            List<String> missing = new ArrayList<>();
            for (String header : EXPECTED_HEADERS) {
                if (response.header(header) == null) {
                    missing.add(header);
                }
            }
            boolean pass = missing.isEmpty();
            return new SecurityFinding(SecurityHttp.now(), category(), "Baseline security headers present",
                    definition.method(), definition.normalizedPath(),
                    pass ? Verdict.PASS : Verdict.FAIL, pass ? Severity.INFO : Severity.LOW,
                    pass ? "All baseline security headers were present."
                            : "Missing headers: " + String.join(", ", missing),
                    "checked: " + String.join(", ", EXPECTED_HEADERS));
        } catch (Exception exception) {
            return notAssessed("Baseline security headers present", definition, exception.getMessage());
        }
    }

    private SecurityFinding checkVerboseErrors(SecurityCheckContext context) {
        try {
            String host = context.combinedInventory().isEmpty() ? context.authHost()
                    : context.combinedInventory().get(0).host();
            String url = context.scheme() + "://" + host + "/vigilx-probe-nonexistent-path-" + System.nanoTime();
            Response response = SecurityHttp.get(url, Map.of("accept", "application/json",
                    "Authorization", "Bearer " + context.validToken()));
            String body = response.asString() == null ? "" : response.asString().toLowerCase(Locale.ROOT);
            boolean leaksInternals = body.contains("stacktrace") || body.contains("at com.")
                    || body.contains("at java.") || body.contains("exception in thread")
                    || body.contains("traceback (most recent call last)");
            return new SecurityFinding(SecurityHttp.now(), category(), "No stack trace leaked on error",
                    "GET", "/vigilx-probe-nonexistent-path", leaksInternals ? Verdict.FAIL : Verdict.PASS,
                    leaksInternals ? Severity.MEDIUM : Severity.INFO,
                    leaksInternals ? "A 404-style request returned what looks like internal stack trace detail."
                            : "A request to a nonexistent path (HTTP " + response.statusCode()
                                    + ") did not leak internal implementation detail.",
                    "HTTP " + response.statusCode());
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(), "No stack trace leaked on error",
                    "GET", "/vigilx-probe-nonexistent-path", Verdict.NOT_ASSESSED, Severity.INFO,
                    "Check could not run: " + exception.getMessage(), null);
        }
    }

    private SecurityFinding checkReflection(SecurityCheckContext context, ApiDefinition definition,
                                            String paramName, String payload, String payloadKind) {
        String probeUrl = replaceQueryParam(context.urlOf(definition), paramName, payload);
        try {
            Response response = SecurityHttp.get(probeUrl, Map.of(
                    "accept", "application/json", "Authorization", "Bearer " + context.validToken()));
            int status = response.statusCode();
            String body = response.asString() == null ? "" : response.asString();

            if (status >= 500) {
                return new SecurityFinding(SecurityHttp.now(), category(),
                        payloadKind + " payload in \"" + paramName + "\" query param",
                        definition.method(), definition.normalizedPath(), Verdict.FAIL, Severity.HIGH,
                        "A " + payloadKind + " payload in the \"" + paramName + "\" parameter caused a "
                                + status + " server error instead of a clean validation response.",
                        "HTTP " + status);
            }
            boolean reflectedUnescaped = body.contains(payload);
            return new SecurityFinding(SecurityHttp.now(), category(),
                    payloadKind + " payload in \"" + paramName + "\" query param",
                    definition.method(), definition.normalizedPath(),
                    reflectedUnescaped ? Verdict.FAIL : Verdict.PASS,
                    reflectedUnescaped ? Severity.HIGH : Severity.INFO,
                    reflectedUnescaped
                            ? "The payload was reflected back unescaped in the response body (possible reflected XSS)."
                            : "The payload was not reflected unescaped and did not cause a server error (HTTP " + status + ").",
                    "HTTP " + status);
        } catch (Exception exception) {
            return notAssessed(payloadKind + " payload in \"" + paramName + "\" query param", definition,
                    exception.getMessage());
        }
    }

    private String replaceQueryParam(String url, String paramName, String value) {
        String encoded = java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        return url.replaceAll("(?i)([?&]" + paramName + "=)[^&]*", "$1" + java.util.regex.Matcher.quoteReplacement(encoded));
    }

    private SecurityFinding notAssessed(String checkName, ApiDefinition definition, String reason) {
        return new SecurityFinding(SecurityHttp.now(), category(), checkName, definition.method(),
                definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                "Check could not run: " + reason, null);
    }
}
