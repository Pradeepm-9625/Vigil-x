package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.restassured.response.Response;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;
import com.vigilx.config.ConfigReader;

/**
 * API4 - Unrestricted Resource Consumption.
 *
 * <p>Real, safe checks performed:
 * <ul>
 *   <li>A small, controlled burst (default 15, configurable, never uncontrolled) of rapid GET calls
 *       to one lightweight endpoint, checking whether rate limiting (HTTP 429) is ever triggered.
 *       Absence of a 429 in a small burst is reported {@code NOT_ASSESSED}, not {@code FAIL}: it does
 *       not prove no protection exists, only that this small, safe sample did not trigger one.</li>
 *   <li>An oversized {@code limit}/{@code page_size} query parameter against a real paginated list
 *       endpoint, checking the API caps what it returns rather than dumping unbounded data.</li>
 * </ul>
 */
public final class ResourceConsumptionCheck implements SecurityCheck {

    private static final Pattern LIMIT_PARAM = Pattern.compile("(?i)([?&](?:limit|page_size)=)(\\d+)");

    @Override
    public OwaspCategory category() {
        return OwaspCategory.API4_RESOURCE_CONSUMPTION;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        List<SecurityFinding> findings = new ArrayList<>();

        context.eligibleGetApis().stream().findFirst()
                .ifPresent(definition -> findings.add(checkRateLimiting(context, definition)));

        for (ApiDefinition definition : context.eligibleGetApis()) {
            if (LIMIT_PARAM.matcher("&" + definition.sampleQuery()).find()) {
                findings.add(checkUnboundedLimit(context, definition));
                break;
            }
        }

        return findings;
    }

    private SecurityFinding checkRateLimiting(SecurityCheckContext context, ApiDefinition definition) {
        int burst = intConfig("apisecurity.security.rate.limit.burst", 15);
        String url = context.urlOf(definition);
        Map<String, String> headers = Map.of("accept", "application/json",
                "Authorization", "Bearer " + context.validToken());

        try {
            boolean sawRateLimit = false;
            int lastStatus = 0;
            for (int i = 0; i < burst; i++) {
                Response response = SecurityHttp.get(url, headers);
                lastStatus = response.statusCode();
                if (lastStatus == 429) {
                    sawRateLimit = true;
                    break;
                }
            }
            if (sawRateLimit) {
                return new SecurityFinding(SecurityHttp.now(), category(),
                        "Rate limiting observed under a small controlled burst (" + burst + " requests)",
                        definition.method(), definition.normalizedPath(), Verdict.PASS, Severity.INFO,
                        "HTTP 429 was returned before completing " + burst + " rapid requests - rate "
                                + "limiting is active on this endpoint.", null);
            }
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Rate limiting observed under a small controlled burst (" + burst + " requests)",
                    definition.method(), definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                    "No HTTP 429 seen across " + burst + " rapid requests (last status " + lastStatus
                            + "). This does not prove the endpoint is unprotected - only that this small, "
                            + "safe sample did not trigger a limit. A meaningful negative result would "
                            + "require a much larger burst, which this framework will not send.", null);
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Rate limiting observed under a small controlled burst", definition.method(),
                    definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                    "Check could not run: " + exception.getMessage(), null);
        }
    }

    private SecurityFinding checkUnboundedLimit(SecurityCheckContext context, ApiDefinition definition) {
        Matcher matcher = LIMIT_PARAM.matcher("&" + definition.sampleQuery());
        String oversized = matcher.find() ? matcher.replaceFirst("$1100000") : definition.sampleQuery();
        String url = context.scheme() + "://" + definition.host() + definition.samplePath()
                + "?" + oversized.substring(1);
        try {
            Response response = SecurityHttp.get(url, Map.of("accept", "application/json",
                    "Authorization", "Bearer " + context.validToken()));
            int status = response.statusCode();
            int returnedCount = countJsonArrayElements(response);
            boolean capped = status >= 400 || returnedCount < 0 || returnedCount <= 5000;
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Oversized page-size parameter is capped, not honored verbatim",
                    definition.method(), definition.normalizedPath(),
                    capped ? Verdict.PASS : Verdict.FAIL, capped ? Severity.INFO : Severity.MEDIUM,
                    capped ? "Requesting limit=100000 did not return an unbounded result (HTTP " + status
                            + (returnedCount >= 0 ? ", " + returnedCount + " item(s)" : "") + ")."
                            : "Requesting limit=100000 returned " + returnedCount + " items - the API "
                                    + "appears to honor an arbitrarily large page size verbatim.",
                    "HTTP " + status);
        } catch (Exception exception) {
            return new SecurityFinding(SecurityHttp.now(), category(),
                    "Oversized page-size parameter is capped, not honored verbatim", definition.method(),
                    definition.normalizedPath(), Verdict.NOT_ASSESSED, Severity.INFO,
                    "Check could not run: " + exception.getMessage(), null);
        }
    }

    private int countJsonArrayElements(Response response) {
        try {
            Object json = response.jsonPath().get();
            if (json instanceof List<?> list) {
                return list.size();
            }
            Object data = response.jsonPath().get("data");
            if (data instanceof List<?> list) {
                return list.size();
            }
            Object items = response.jsonPath().get("items");
            if (items instanceof List<?> list) {
                return list.size();
            }
            return -1;
        } catch (Exception exception) {
            return -1;
        }
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
