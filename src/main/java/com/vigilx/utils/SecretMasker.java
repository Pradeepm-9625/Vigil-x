package com.vigilx.utils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Masks secrets out of captured HTTP evidence (headers and bodies) before it is ever written to a
 * report, log, or inventory file.
 *
 * <p>Shared by every consumer of captured API traffic - the API inventory, REST Assured evidence,
 * curl/security-test evidence, and ZAP reporting - so masking rules live in exactly one place and
 * behave identically everywhere. Never throws: masking a malformed value falls back to treating it
 * as an opaque string rather than failing the caller.
 */
public final class SecretMasker {

    /** Header names whose value is always replaced, regardless of content. */
    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token", "x-access-token", "x-refresh-token", "x-csrf-token");

    /** JSON body / form-field keys whose value is always replaced, regardless of nesting. */
    private static final Set<String> SENSITIVE_BODY_KEYS = Set.of(
            "password", "confirmpassword", "oldpassword", "newpassword",
            "accesstoken", "access_token", "refreshtoken", "refresh_token", "token", "idtoken", "id_token",
            "secret", "apikey", "api_key", "clientsecret", "client_secret",
            "sessionid", "session_id", "otp", "pin", "authorization");

    private static final String MASKED = "********";
    private static final int DEFAULT_MAX_BODY_CHARACTERS = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private SecretMasker() {
    }

    /** True when the header name (case-insensitive) is one that must always be masked. */
    public static boolean isSensitiveHeader(String name) {
        return name != null && SENSITIVE_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** True when the body/form field key (case-insensitive) is one that must always be masked. */
    public static boolean isSensitiveBodyKey(String key) {
        return key != null && SENSITIVE_BODY_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns a new header map with every sensitive value replaced. Non-sensitive headers pass
     * through unchanged. A {@code null} map returns an empty map.
     */
    public static Map<String, String> maskHeaders(Map<String, String> headers) {
        Map<String, String> masked = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers == null) {
            return masked;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (isSensitiveHeader(name)) {
                masked.put(name, maskValue(entry.getValue()));
            } else {
                masked.put(name, entry.getValue());
            }
        }
        return masked;
    }

    /**
     * Masks a request/response body, truncating the result afterwards so a masked-but-huge body
     * cannot bloat a report. Bodies that parse as JSON are masked key-by-key (recursively, arrays
     * included); anything else falls back to a best-effort regex mask over common
     * {@code "key":"value"} / {@code key=value} shapes, so a non-JSON body is still safe to store.
     */
    public static String maskBody(String body) {
        return maskBody(body, DEFAULT_MAX_BODY_CHARACTERS);
    }

    public static String maskBody(String body, int maxCharacters) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String masked;
        try {
            JsonNode root = JSON.readTree(body);
            maskJsonNode(root);
            masked = JSON.writeValueAsString(root);
        } catch (Exception notJson) {
            masked = maskPlainText(body);
        }
        if (masked.length() > maxCharacters) {
            return masked.substring(0, maxCharacters) + " ...[truncated]";
        }
        return masked;
    }

    private static void maskJsonNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.fieldNames().forEachRemaining(field -> {
                if (isSensitiveBodyKey(field)) {
                    object.put(field, MASKED);
                } else {
                    maskJsonNode(object.get(field));
                }
            });
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode element : array) {
                maskJsonNode(element);
            }
        }
    }

    /** Best-effort masking for non-JSON bodies: {@code "key": "value"}, {@code key=value}. */
    private static String maskPlainText(String body) {
        String result = body;
        for (String key : SENSITIVE_BODY_KEYS) {
            result = result.replaceAll(
                    "(?i)(\"" + key + "\"\\s*:\\s*\")[^\"]*(\")", "$1" + MASKED + "$2");
            result = result.replaceAll(
                    "(?i)(\\b" + key + "\\s*=\\s*)[^&\\s]*", "$1" + MASKED);
        }
        return result;
    }

    private static String maskValue(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "Bearer " + MASKED;
        }
        return MASKED;
    }
}
