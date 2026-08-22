package com.vigilx.apisecurity.security;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import io.restassured.RestAssured;
import io.restassured.response.Response;

/** Small shared HTTP helper so every check does not duplicate REST Assured boilerplate. */
final class SecurityHttp {

    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SecurityHttp() {
    }

    static String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    /** GET with the given headers (may be empty - i.e. unauthenticated). Never throws. */
    static Response get(String url, Map<String, String> headers) {
        var request = RestAssured.given();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request = request.header(header.getKey(), header.getValue());
        }
        return request.when().get(url);
    }

    static Response postJson(String url, Object body, Map<String, String> headers) {
        var request = RestAssured.given().contentType("application/json").body(body);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request = request.header(header.getKey(), header.getValue());
        }
        return request.when().post(url);
    }

    /**
     * Sends any method with a raw string body and an explicit Content-Type - what the negative-test
     * engine needs to replay a real captured body (or a deliberately mutated/malformed one) against
     * POST/PUT/PATCH/DELETE, and to probe an endpoint with a method it does not normally receive.
     */
    static Response request(String method, String url, String body, String contentType, Map<String, String> headers) {
        var request = RestAssured.given();
        if (body != null && !body.isBlank()) {
            request = request.body(body);
        }
        if (contentType != null && !contentType.isBlank()) {
            request = request.contentType(contentType);
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request = request.header(header.getKey(), header.getValue());
        }
        return switch (method.toUpperCase(java.util.Locale.ROOT)) {
            case "GET" -> request.when().get(url);
            case "POST" -> request.when().post(url);
            case "PUT" -> request.when().put(url);
            case "PATCH" -> request.when().patch(url);
            case "DELETE" -> request.when().delete(url);
            case "HEAD" -> request.when().head(url);
            case "OPTIONS" -> request.when().options(url);
            default -> request.when().get(url);
        };
    }
}
