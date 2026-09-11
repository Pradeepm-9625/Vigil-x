package com.vigilx.apisecurity.inventory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Streams a HAR (HTTP Archive) file entry-by-entry rather than loading it as one JSON tree, so a
 * multi-hundred-megabyte capture (embedded snapshot/media content included) parses without holding
 * the whole file in memory at once - only one {@code log.entries[]} element at a time is ever
 * materialized.
 *
 * <p>Extracts only what the API inventory needs - method, URL, response status - and deliberately
 * ignores request/response bodies here: HAR entries routinely embed large base64 media payloads that
 * this comparison has no use for and that would bloat every downstream report.
 *
 * <p>The HAR is a discovery source only, per the framework's rule: entries are turned into
 * {@link ApiDefinition}s here, but nothing from a HAR is ever treated as "already captured" -
 * that decision belongs to {@link ApiComparisonService}.
 */
public final class HarParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = new JsonFactory();

    /** Extensions that identify a static asset even when it was requested through an API-style path. */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".js", ".mjs", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".bmp",
            ".woff", ".woff2", ".ttf", ".otf", ".eot", ".map", ".html");

    private HarParser() {
    }

    /** Parses every entry in the HAR into an {@link ApiDefinition}; malformed entries are skipped. */
    public static List<ApiDefinition> parse(Path harFile) throws IOException {
        List<ApiDefinition> entries = new ArrayList<>();
        try (JsonParser parser = FACTORY.createParser(harFile.toFile())) {
            if (!advanceToField(parser, "entries") || parser.nextToken() != JsonToken.START_ARRAY) {
                System.err.println("[HAR PARSER] No log.entries array found in " + harFile);
                return entries;
            }
            int skipped = 0;
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode entry = MAPPER.readTree(parser);
                ApiDefinition definition = toApiDefinition(entry);
                if (definition != null) {
                    entries.add(definition);
                } else {
                    skipped++;
                }
            }
            if (skipped > 0) {
                System.out.println("[HAR PARSER] Skipped " + skipped + " malformed/unusable entr"
                        + (skipped == 1 ? "y" : "ies") + " in " + harFile);
            }
        }
        return entries;
    }

    /** Advances the parser until a field named {@code fieldName} is the current token, at any depth. */
    private static boolean advanceToField(JsonParser parser, String fieldName) throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
            if (token == JsonToken.FIELD_NAME && fieldName.equals(parser.currentName())) {
                return true;
            }
        }
        return false;
    }

    private static ApiDefinition toApiDefinition(JsonNode entry) {
        try {
            JsonNode request = entry.path("request");
            JsonNode response = entry.path("response");
            String method = request.path("method").asText("");
            String url = request.path("url").asText("");
            if (method.isBlank() || url.isBlank() || isStaticAsset(url)) {
                return null;
            }
            int status = response.path("status").asInt(0);
            // Deliberately NOT inspecting the HAR for an Authorization/Cookie header: this file's own
            // "creator" field identifies it as a Chrome DevTools ("WebInspector") export, and Chrome
            // redacts Authorization/Cookie from every entry on export (confirmed: zero of 3890 entries
            // carry either header). Asserting "no auth" from that would be reporting an export artifact
            // as a security fact, so HAR-sourced entries always carry authObservedInSample=unknown
            // (null); only ApiMonitor's own live capture - never redacted - is trusted for this signal.
            String requestBody = maskedRequestBody(request);
            return ApiDefinition.fromRawUrl(method, url, status, ApiDefinition.Source.HAR, null, requestBody);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * The real captured request body (masked), for the negative-test engine's body-mutation cases.
     * Unlike the Authorization header, HAR request bodies are NOT redacted by Chrome's export - but
     * they can still carry secrets (e.g. a password field on a login-shaped call), so this is masked
     * exactly like every other captured body in this framework before it is ever retained.
     */
    private static String maskedRequestBody(JsonNode request) {
        JsonNode postData = request.path("postData");
        String text = postData.path("text").asText("");
        if (text.isBlank()) {
            return null;
        }
        return com.vigilx.utils.SecretMasker.maskBody(text, 4000);
    }

    private static boolean isStaticAsset(String url) {
        String withoutQuery = url.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        for (String extension : STATIC_EXTENSIONS) {
            if (withoutQuery.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
