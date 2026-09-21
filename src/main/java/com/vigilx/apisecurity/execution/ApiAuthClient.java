package com.vigilx.apisecurity.execution;

import java.util.Map;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import com.vigilx.config.ConfigReader;
import com.vigilx.utils.SecretMasker;

/**
 * Logs in against the application's own {@code /auth/login} endpoint to obtain a bearer token for
 * REST Assured calls - independent of, and never touching, the Playwright browser session.
 * Credentials come from the same {@code username}/{@code password} config keys the UI login already
 * uses, so there is exactly one place credentials are configured.
 *
 * <p>The endpoint is itself rate-limited by the application (observed: 5 attempts per window), so
 * this is called <strong>once</strong> per run and the token is reused for every subsequent request -
 * never re-login per API call.
 */
public final class ApiAuthClient {

    private ApiAuthClient() {
    }

    /** Logs in once and returns the bearer token. Throws if login fails - callers must not proceed. */
    public static String login(String scheme, String host) {
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String url = scheme + "://" + host + ConfigReader.getOrDefault("apisecurity.auth.login.path", "/auth/login");

        Response response = RestAssured.given()
                .header("accept", "application/json")
                .header("instance", "web")
                .contentType("application/json")
                .body(Map.of("email", username, "password", password))
                .when()
                .post(url);

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IllegalStateException("API auth login failed: HTTP " + response.statusCode()
                    + " from " + url + " - body: " + SecretMasker.maskBody(response.asString()));
        }

        String token = response.jsonPath().getString("accessToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("API auth login succeeded (HTTP " + response.statusCode()
                    + ") but no accessToken was found in the response from " + url);
        }
        return token;
    }
}
