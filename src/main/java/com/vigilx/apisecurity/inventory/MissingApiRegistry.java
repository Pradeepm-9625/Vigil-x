package com.vigilx.apisecurity.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.config.ConfigReader;

/**
 * The manually curated list of APIs confirmed - by a real {@link ApiComparisonService} run, never
 * by inspecting a HAR on its own - to be missing from {@code ApiMonitor}'s capture.
 *
 * <p>Data-driven on purpose: {@code apisecurity/missing-apis.json} is the single source of truth, so
 * updating the registry never requires a Java code change. This class only loads and validates that
 * file; it must never hard-code API entries itself. {@link MissingApiRegistryUpdater} is what
 * actually revalidates/regenerates it.
 *
 * <p>Reads the real source file first (default {@code src/test/resources/apisecurity/missing-apis.json},
 * relative to the working directory Maven/Surefire already run from) rather than only the classpath
 * copy - so a registry regenerated earlier in the very same {@code mvn test} invocation is picked up
 * immediately, not only after the next build's resource-copy step. Falls back to the classpath copy
 * for any context where the source tree isn't available (e.g. a packaged jar).
 */
public final class MissingApiRegistry {

    private static final String CLASSPATH_RESOURCE = "apisecurity/missing-apis.json";
    private static final String DEFAULT_SOURCE_PATH = "src/test/resources/apisecurity/missing-apis.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MissingApiRegistry() {
    }

    /** The real source file this registry is read from and revalidated into. */
    public static Path sourceFile() {
        return Paths.get(ConfigReader.getOrDefault("apisecurity.missing.registry.path", DEFAULT_SOURCE_PATH));
    }

    /** Loads the registry. Returns an empty list - never throws - if it is missing or empty. */
    public static List<ApiDefinition> getMissingApis() {
        Path source = sourceFile();
        try {
            if (Files.exists(source)) {
                return parse(MAPPER.readTree(source.toFile()));
            }
        } catch (IOException exception) {
            System.err.println("[MISSING API REGISTRY] Could not read " + source + ": " + exception.getMessage());
        }

        try (InputStream stream = MissingApiRegistry.class.getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (stream == null) {
                System.err.println("[MISSING API REGISTRY] Neither " + source + " nor the classpath resource "
                        + CLASSPATH_RESOURCE + " was found; treating the registry as empty.");
                return new ArrayList<>();
            }
            return parse(MAPPER.readTree(stream));
        } catch (IOException exception) {
            System.err.println("[MISSING API REGISTRY] Could not read " + CLASSPATH_RESOURCE + ": "
                    + exception.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<ApiDefinition> parse(JsonNode root) {
        List<ApiDefinition> definitions = new ArrayList<>();
        for (JsonNode api : root.path("apis")) {
            String query = api.path("query").asText("");
            JsonNode authNode = api.path("authObservedInSample");
            Boolean authObserved = authNode.isMissingNode() || authNode.isNull() ? null : authNode.asBoolean();
            definitions.add(ApiDefinition.of(
                    api.path("method").asText(""),
                    api.path("host").asText(""),
                    api.path("path").asText("/"),
                    query.isBlank() ? null : query,
                    api.path("expectedStatus").asInt(0),
                    ApiDefinition.Source.MANUAL, authObserved,
                    api.path("sampleRequestBody").asText(null)));
        }
        return definitions;
    }
}
