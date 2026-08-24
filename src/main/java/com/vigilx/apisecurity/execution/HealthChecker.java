package com.vigilx.apisecurity.execution;

import io.restassured.RestAssured;

/**
 * Minimal pre-flight/mid-flight health check (brief Phase 33): a lightweight, unauthenticated GET
 * against a known-public endpoint. Used to gate high-impact phases (security probing, ZAP, JMeter) -
 * if the app is not responding, those phases must not fire more traffic at it.
 */
public final class HealthChecker {

    private HealthChecker() {
    }

    /** True when the app answers at all with anything short of a server error. Never throws. */
    public static boolean isHealthy(String scheme, String host) {
        try {
            int status = RestAssured.given()
                    .header("accept", "application/json")
                    .when()
                    .get(scheme + "://" + host + "/initial-flow/status")
                    .statusCode();
            return status > 0 && status < 500;
        } catch (Exception exception) {
            return false;
        }
    }
}
