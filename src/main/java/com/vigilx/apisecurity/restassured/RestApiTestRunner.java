package com.vigilx.apisecurity.restassured;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;

import com.vigilx.apisecurity.execution.ApiAuthClient;
import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.execution.ApiTestResult;
import com.vigilx.apisecurity.execution.ApiTestResult.Outcome;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.config.ConfigReader;

/**
 * Fires REST Assured GET requests at the eligible slice of the combined API inventory - real HTTP
 * calls against the live application, so this stays deliberately conservative: read-only by
 * {@link ApiExecutionPolicy}, rate limited between calls, and equipped with a circuit breaker so an
 * unhealthy backend is never hammered (Step 17 of the brief: stop, don't retry into a dead server).
 *
 * <p>Purpose is functional validation - status, response time, content type, JSON structure - not
 * load testing; see the JMeter phase for that. Every non-GET API in the inventory is reported as
 * "present, not auto-executed", never silently dropped from the report.
 */
public final class RestApiTestRunner {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ApiExecutionPolicy policy;
    private final String scheme;
    private final long delayMs;
    private final long responseTimeThresholdMs;
    private final int circuitBreakerThreshold;
    private final int maxApis;

    public RestApiTestRunner() {
        this.policy = ApiExecutionPolicy.loadDefault();
        this.scheme = ConfigReader.getOrDefault("apisecurity.restassured.scheme", "http");
        this.delayMs = longConfig("apisecurity.restassured.delay.ms", 300);
        this.responseTimeThresholdMs = longConfig("apisecurity.restassured.response.time.threshold.ms", 5000);
        this.circuitBreakerThreshold = (int) longConfig("apisecurity.restassured.circuit.breaker.consecutive.failures", 3);
        this.maxApis = (int) longConfig("apisecurity.restassured.max.apis", 0);
        configureTimeout(longConfig("apisecurity.restassured.timeout.ms", 15000));
    }

    /** Everything the run produced: attempted results, and APIs never attempted, each with a reason. */
    public static final class RunOutcome {
        public final List<ApiTestResult> results = new ArrayList<>();
        public final Map<ApiDefinition, String> notEligible = new LinkedHashMap<>();
        public boolean authFailed;
        public String authFailureReason;
    }

    public RunOutcome run(List<ApiDefinition> combinedInventory) {
        RunOutcome outcome = new RunOutcome();

        List<ApiDefinition> eligible = new ArrayList<>();
        for (ApiDefinition definition : combinedInventory) {
            String reason = policy.ineligibilityReason(definition);
            if (reason == null) {
                eligible.add(definition);
            } else {
                outcome.notEligible.put(definition, reason);
            }
        }

        if (eligible.isEmpty()) {
            System.out.println("[REST ASSURED] No eligible (GET) APIs to execute.");
            return outcome;
        }

        String authHost = resolveAuthHost(combinedInventory, eligible);
        String token;
        try {
            token = ApiAuthClient.login(scheme, authHost);
            System.out.println("[REST ASSURED] Authenticated against " + scheme + "://" + authHost);
        } catch (Exception exception) {
            outcome.authFailed = true;
            outcome.authFailureReason = exception.getMessage();
            System.err.println("[REST ASSURED] Aborting: authentication failed - " + exception.getMessage());
            return outcome;
        }

        if (maxApis > 0 && eligible.size() > maxApis) {
            System.out.println("[REST ASSURED] Capping this run to the first " + maxApis + " of "
                    + eligible.size() + " eligible APIs (apisecurity.restassured.max.apis).");
            eligible = eligible.subList(0, maxApis);
        }

        int consecutiveFailures = 0;
        boolean circuitOpen = false;

        for (ApiDefinition definition : eligible) {
            if (circuitOpen) {
                outcome.results.add(skipped(definition, "circuit breaker open: "
                        + circuitBreakerThreshold + " consecutive failures - remaining APIs skipped "
                        + "to avoid hammering an unhealthy server"));
                continue;
            }

            ApiTestResult result = executeOne(definition, token);
            outcome.results.add(result);

            if (result.outcome() == Outcome.FAIL) {
                consecutiveFailures++;
                if (consecutiveFailures >= circuitBreakerThreshold) {
                    circuitOpen = true;
                    System.err.println("[REST ASSURED] Circuit breaker tripped after "
                            + consecutiveFailures + " consecutive failures. Halting further execution.");
                }
            } else {
                consecutiveFailures = 0;
            }

            sleep(delayMs);
        }

        return outcome;
    }

    private ApiTestResult executeOne(ApiDefinition definition, String token) {
        String path = definition.samplePath();
        String query = definition.sampleQuery();
        String url = scheme + "://" + definition.host() + path + (query == null || query.isBlank() ? "" : "?" + query);
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        int expectedStatus = definition.sampleStatus() > 0 ? definition.sampleStatus() : 200;

        try {
            long started = System.nanoTime();
            Response response = RestAssured.given()
                    .header("accept", "application/json")
                    .header("instance", "web")
                    .header("Authorization", "Bearer " + token)
                    .when()
                    .get(url);
            long responseTimeMs = (System.nanoTime() - started) / 1_000_000;

            int actualStatus = response.statusCode();
            String contentType = response.contentType();
            boolean withinThreshold = responseTimeMs <= responseTimeThresholdMs;

            Outcome outcome;
            String note = null;

            if (actualStatus == expectedStatus) {
                outcome = Outcome.PASS;
            } else if (isSuccessFamily(actualStatus) && isSuccessFamily(expectedStatus)) {
                outcome = Outcome.PASS;
                note = "status differs from sample (expected " + expectedStatus + ", got " + actualStatus
                        + ") but both are 2xx";
            } else if (actualStatus >= 500) {
                outcome = Outcome.FAIL;
                note = "server error";
            } else if (actualStatus == 404 && definition.normalizedPath().contains("{id}")) {
                outcome = Outcome.FAIL;
                note = "unexpected 404 - possibly a stale sample ID from the original capture, not "
                        + "necessarily a regression";
            } else {
                outcome = Outcome.FAIL;
                note = "unexpected status (expected " + expectedStatus + ")";
            }

            if (outcome == Outcome.PASS && !contentTypePresent(contentType, response)) {
                outcome = Outcome.FAIL;
                note = "response body present but Content-Type header missing";
            } else if (outcome == Outcome.PASS && isJson(contentType) && !parsesAsJson(response)) {
                outcome = Outcome.FAIL;
                note = "Content-Type claims JSON but body did not parse as JSON";
            }

            if (!withinThreshold) {
                note = (note == null ? "" : note + "; ") + "response time " + responseTimeMs
                        + "ms exceeds " + responseTimeThresholdMs + "ms threshold (not counted as a failure)";
            }

            return new ApiTestResult(timestamp, definition.method(), definition.host(), path, url,
                    expectedStatus, actualStatus, responseTimeMs, withinThreshold, contentType, outcome, note);

        } catch (Exception exception) {
            return new ApiTestResult(timestamp, definition.method(), definition.host(), path, url,
                    expectedStatus, 0, 0, false, "", Outcome.FAIL,
                    "request error: " + exception.getMessage());
        }
    }

    private static boolean contentTypePresent(String contentType, Response response) {
        String body = response.asString();
        if (body == null || body.isBlank()) {
            return true;
        }
        return contentType != null && !contentType.isBlank();
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("json");
    }

    private static boolean parsesAsJson(Response response) {
        try {
            String body = response.asString();
            if (body == null || body.isBlank()) {
                return true;
            }
            response.jsonPath().get();
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isSuccessFamily(int status) {
        return status >= 200 && status < 300;
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
        return eligible.get(0).host();
    }

    private static ApiTestResult skipped(ApiDefinition definition, String reason) {
        return new ApiTestResult(LocalDateTime.now().format(TIMESTAMP), definition.method(), definition.host(),
                definition.samplePath(), "", definition.sampleStatus(), 0, 0, false, "", Outcome.SKIPPED, reason);
    }

    private void configureTimeout(long timeoutMs) {
        try {
            RestAssured.config = RestAssuredConfig.config().httpClient(
                    HttpClientConfig.httpClientConfig()
                            .setParam("http.connection.timeout", (int) timeoutMs)
                            .setParam("http.socket.timeout", (int) timeoutMs));
        } catch (Exception exception) {
            System.err.println("[REST ASSURED] Could not configure request timeout, using defaults: "
                    + exception.getMessage());
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
