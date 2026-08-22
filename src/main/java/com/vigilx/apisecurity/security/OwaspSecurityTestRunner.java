package com.vigilx.apisecurity.security;

import java.util.ArrayList;
import java.util.List;

import com.vigilx.apisecurity.execution.ApiAuthClient;
import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiComparisonService.ComparisonResult;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.config.ConfigReader;

/**
 * Runs every {@link SecurityCheck} against the final combined API inventory and produces one flat
 * list of {@link SecurityFinding}s, one per concrete test actually performed (or explicitly not
 * performed, with a reason).
 *
 * <p>Same safety posture as {@link com.vigilx.apisecurity.restassured.RestApiTestRunner}: real HTTP
 * calls against the live application, GET-only by {@link ApiExecutionPolicy}, authenticates once and
 * reuses the token, and aborts the whole run if authentication itself fails rather than probing an
 * environment it cannot even log into.
 */
public final class OwaspSecurityTestRunner {

    private final ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
    private final String scheme;

    public OwaspSecurityTestRunner() {
        this.scheme = ConfigReader.getOrDefault("apisecurity.restassured.scheme", "http");
    }

    public static final class RunResult {
        public final List<SecurityFinding> findings = new ArrayList<>();
        public boolean authFailed;
        public String authFailureReason;
    }

    public RunResult run(List<ApiDefinition> combinedInventory, ComparisonResult comparisonResult) {
        RunResult result = new RunResult();

        List<ApiDefinition> eligible = combinedInventory.stream()
                .filter(policy::isEligibleForFunctionalTest)
                .toList();

        String authHost = resolveAuthHost(combinedInventory, eligible);
        String token;
        try {
            token = ApiAuthClient.login(scheme, authHost);
        } catch (Exception exception) {
            result.authFailed = true;
            result.authFailureReason = exception.getMessage();
            System.err.println("[OWASP SECURITY] Aborting: authentication failed - " + exception.getMessage());
            return result;
        }

        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        SecurityCheckContext context = new SecurityCheckContext(scheme, authHost, token, username, password,
                eligible, combinedInventory, comparisonResult);

        List<SecurityCheck> checks = List.of(
                new BolaCheck(),
                new BrokenAuthenticationCheck(),
                new SensitiveDataExposureCheck(),
                new ResourceConsumptionCheck(),
                NotAssessedCheck.brokenFunctionLevelAuthorization(),
                NotAssessedCheck.sensitiveBusinessFlows(),
                new SsrfProbeCheck(),
                new SecurityMisconfigurationCheck(),
                new MethodNegativeCheck(),
                new InputValidationNegativeCheck(),
                new InventoryManagementCheck(),
                NotAssessedCheck.unsafeConsumption());

        for (SecurityCheck check : checks) {
            try {
                result.findings.addAll(check.run(context));
            } catch (Exception exception) {
                System.err.println("[OWASP SECURITY] " + check.category().id() + " check threw: "
                        + exception.getMessage());
                result.findings.add(new SecurityFinding(SecurityHttp.now(), check.category(),
                        "Check execution", null, null, SecurityFinding.Verdict.NOT_ASSESSED,
                        SecurityFinding.Severity.INFO, "Check failed to run: " + exception.getMessage(), null));
            }
        }

        return result;
    }

    private static String resolveAuthHost(List<ApiDefinition> combinedInventory, List<ApiDefinition> eligible) {
        String configured = ConfigReader.getOrDefault("apisecurity.auth.host", "");
        if (!configured.isBlank()) {
            return configured;
        }
        for (ApiDefinition definition : combinedInventory) {
            if ("/auth/login".equals(definition.normalizedPath())) {
                return definition.host();
            }
        }
        return eligible.isEmpty() ? "" : eligible.get(0).host();
    }
}
