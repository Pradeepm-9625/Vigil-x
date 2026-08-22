package com.vigilx.apisecurity.inventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilx.monitoring.ApiMonitor;

/**
 * Read-only adapter over {@code ApiMonitor}'s existing public accessors and its persisted inventory
 * file. Never calls anything that could change {@code ApiMonitor}'s state or behavior.
 *
 * <p>Two ways to obtain the baseline, matching how this module actually runs relative to the soak
 * suite (Step 12 of the brief: a separate execution, not the same JVM):
 * <ul>
 *   <li>{@link #readInProcess()} - only useful when this code runs in the very same JVM that just
 *       did the Playwright navigation with {@code api.inventory.enabled=true}.</li>
 *   <li>{@link #readFromFile(Path)} - the normal path: read the {@code api-inventory.json} that a
 *       prior, separate capture run already wrote via {@code ApiMonitor.writeInventoryReport()}.</li>
 * </ul>
 */
public final class ApiMonitorInventoryReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiMonitorInventoryReader() {
    }

    /** Reads whatever this JVM's {@code ApiMonitor} has captured so far. Empty unless the opt-in flag was on. */
    public static List<ApiDefinition> readInProcess() {
        List<ApiDefinition> definitions = new ArrayList<>();
        for (ApiMonitor.ApiInventoryEntry entry : ApiMonitor.getInventory()) {
            boolean authObserved = entry.requestHeaders().keySet().stream()
                    .anyMatch(name -> "authorization".equalsIgnoreCase(name));
            String requestBody = entry.requestBody() == null || entry.requestBody().isBlank()
                    ? null : entry.requestBody();
            definitions.add(ApiDefinition.of(entry.method(), entry.host(), entry.path(), entry.query(),
                    entry.status(), ApiDefinition.Source.API_MONITOR, authObserved, requestBody));
        }
        return definitions;
    }

    /**
     * Reads a previously written {@code api-inventory.json} (the shape {@code ApiMonitor.writeInventoryReport()}
     * produces). Returns an empty list - never throws - when the file does not exist yet, since that
     * simply means no capture run has happened.
     */
    public static List<ApiDefinition> readFromFile(Path inventoryJsonFile) {
        List<ApiDefinition> definitions = new ArrayList<>();
        if (inventoryJsonFile == null || !Files.exists(inventoryJsonFile)) {
            System.out.println("[API MONITOR INVENTORY READER] No inventory file at "
                    + inventoryJsonFile + " - treating ApiMonitor's captured set as empty. "
                    + "Run a capture pass with api.inventory.enabled=true to populate it.");
            return definitions;
        }
        try {
            JsonNode root = MAPPER.readTree(inventoryJsonFile.toFile());
            for (JsonNode node : root) {
                String method = node.path("method").asText("");
                String host = node.path("host").asText("");
                String path = node.path("path").asText("/");
                String query = node.path("query").asText("");
                int status = node.path("statusCode").asInt(0);
                if (method.isBlank()) {
                    continue;
                }
                boolean authObserved = hasAuthorizationHeader(node.path("requestHeaders"));
                String requestBody = node.path("requestBody").asText("");
                definitions.add(ApiDefinition.of(method, host, path, query.isBlank() ? null : query,
                        status, ApiDefinition.Source.API_MONITOR, authObserved,
                        requestBody.isBlank() ? null : requestBody));
            }
        } catch (IOException exception) {
            System.err.println("[API MONITOR INVENTORY READER] Could not read " + inventoryJsonFile
                    + ": " + exception.getMessage());
        }
        return definitions;
    }

    /** True when the captured request headers include an Authorization key - real evidence, not a guess. */
    private static boolean hasAuthorizationHeader(JsonNode requestHeaders) {
        var fieldNames = requestHeaders.fieldNames();
        while (fieldNames.hasNext()) {
            if ("authorization".equalsIgnoreCase(fieldNames.next())) {
                return true;
            }
        }
        return false;
    }
}
