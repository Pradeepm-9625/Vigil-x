package com.vigilx.apisecurity.inventory;

import java.util.regex.Pattern;

/**
 * Turns a raw URL path into a stable template by replacing segments that are clearly a resource
 * identifier - not a fixed part of the route - with a placeholder.
 *
 * <p>Deliberately conservative: a segment is only ever replaced when it matches one of a small set
 * of well-known identifier shapes, so two genuinely different endpoints are never accidentally
 * merged into one (e.g. {@code /devices} and {@code /device-groups} stay distinct).
 */
public final class ApiNormalizer {

    private static final Pattern UUID_FULL = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern UUID_ANYWHERE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NUMERIC_FULL = Pattern.compile("^\\d+$");
    private static final Pattern MONGO_ID_FULL = Pattern.compile("^[0-9a-fA-F]{24}$");

    /**
     * Segment prefixes that mark a generated, per-session token rather than a fixed route piece -
     * observed in this app's media-signaling traffic (e.g. {@code temp-rtsp-<generated>-<ts>}).
     */
    private static final String[] SESSION_SEGMENT_PREFIXES = { "temp-rtsp-" };

    private ApiNormalizer() {
    }

    /** Normalizes one URL path into a method-agnostic template, e.g. {@code /devices/{id}/recording}. */
    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            normalized.append('/').append(normalizeSegment(segment));
        }
        return normalized.length() == 0 ? "/" : normalized.toString();
    }

    private static String normalizeSegment(String segment) {
        if (UUID_FULL.matcher(segment).matches()
                || MONGO_ID_FULL.matcher(segment).matches()
                || NUMERIC_FULL.matcher(segment).matches()) {
            return "{id}";
        }
        if (UUID_ANYWHERE.matcher(segment).find() || hasSessionPrefix(segment)) {
            // A whole segment built around a generated identifier (e.g. a WebRTC/WHEP stream
            // session slug: "<uuid>-1920x1080_320") is still one variable segment, not a fixed
            // route piece - collapsing it avoids treating every stream session as its own endpoint.
            return "{streamSessionId}";
        }
        return segment;
    }

    private static boolean hasSessionPrefix(String segment) {
        for (String prefix : SESSION_SEGMENT_PREFIXES) {
            if (segment.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
