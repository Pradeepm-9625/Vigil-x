package com.vigilx.apisecurity.inventory;

import java.util.Locale;

/**
 * Classifies one API into a load-testing category using its method and normalized path - never a
 * hardcoded per-endpoint list, so a newly discovered endpoint is classified automatically. Path
 * keywords are checked first (an endpoint's purpose - auth, streaming, device control - matters more
 * for choosing a safe load profile than its HTTP method alone), falling back to a method-based
 * READ/CREATE/UPDATE/DELETE split.
 */
public final class ApiClassifier {

    private ApiClassifier() {
    }

    public static ApiCategory classify(ApiDefinition definition) {
        String path = definition.normalizedPath().toLowerCase(Locale.ROOT);
        String method = definition.method();

        if (containsAny(path, "/auth/", "login", "logout", "otp", "password", "unlock-user")) {
            return ApiCategory.AUTH;
        }
        if (containsAny(path, "/streaming/", "/recording/", "live", "playback", "/whep", "get-live", "/archive")) {
            return ApiCategory.STREAMING;
        }
        if (containsAny(path, "ptz", "encoder", "stream-settings", "video-image", "network-settings")) {
            return ApiCategory.CONTROL;
        }
        if (containsAny(path, "export", "report", "audit", "analytics", "va-events")) {
            return ApiCategory.REPORTING;
        }
        if (containsAny(path, "/devices", "/device/", "onboarding", "health/camera", "master-configurations")) {
            return ApiCategory.DEVICE;
        }

        return switch (method) {
            case "GET" -> ApiCategory.READ;
            case "POST" -> ApiCategory.CREATE;
            case "PUT", "PATCH" -> ApiCategory.UPDATE;
            case "DELETE" -> ApiCategory.DELETE;
            default -> ApiCategory.OTHER;
        };
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
