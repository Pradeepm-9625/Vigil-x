package com.vigilx.apisecurity.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.config.ConfigReader;
import com.vigilx.monitoring.ApiMonitor;
import com.vigilx.monitoring.ApiMonitor.CapturedRequest;

/**
 * Generates one JMeter {@code .jmx} plan per soak run from EVERY request occurrence the existing
 * {@code ApiMonitor} captured during that run - in captured order, duplicates kept, all HTTP methods -
 * and a summary of the same dataset (counts per method, samplers written, mismatch, anything that
 * could not be converted). Purely additive: called once at the very end of a soak execution, after
 * every existing step has run; any failure here is caught and logged and can never affect the soak
 * run's own result or existing output.
 *
 * <p>The report numbers and the JMX come from the same {@code ApiMonitor.getAllCapturedRequests()}
 * list; there is no separate hard-coded API list. Reuses {@link LoadTestPlanBuilder#buildOrdered} -
 * the existing JMX writer's sampler/assertion/extractor XML - rather than a second JMX writer.
 */
public final class SoakApiJmxGenerator {

    public static final String JMX_FILE_NAME = "VigilX_SoakTest_All_APIs.jmx";

    /** Transport/browser-managed headers a JMeter sampler sets itself - never replayed from capture. */
    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "authorization", "cookie", "host", "content-length", "connection", "accept-encoding",
            "user-agent", "referer", "origin", "accept", "accept-language", "cache-control", "pragma",
            "priority", "if-none-match", "if-modified-since", "upgrade-insecure-requests", "instance");

    private SoakApiJmxGenerator() {
    }

    /** One captured request that could not become a sampler - always reported, never silent. */
    private static final class Unconverted {
        final CapturedRequest request;
        final String reason;

        Unconverted(CapturedRequest request, String reason) {
            this.request = request;
            this.reason = reason;
        }
    }

    /**
     * Writes {@code <soakRunDirectory>/jmx/VigilX_SoakTest_All_APIs.jmx} plus
     * {@code soak-api-summary.txt} beside it. Returns the JMX path, or {@code null} on failure
     * (non-fatal to the caller).
     */
    public static Path generate(Path soakRunDirectory, String planName) {
        try {
            List<CapturedRequest> captured = ApiMonitor.getAllCapturedRequests();

            Map<String, Integer> methodCounts = new LinkedHashMap<>();
            for (String method : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE"}) {
                methodCounts.put(method, 0);
            }
            int other = 0;
            for (CapturedRequest request : captured) {
                if (methodCounts.containsKey(request.method)) {
                    methodCounts.merge(request.method, 1, Integer::sum);
                } else {
                    other++;
                }
            }

            List<ApiDefinition> definitions = new ArrayList<>();
            List<Map<String, String>> extraHeaders = new ArrayList<>();
            List<String> schemes = new ArrayList<>();
            List<Unconverted> unconverted = new ArrayList<>();
            for (CapturedRequest request : captured) {
                String problem = conversionProblem(request);
                if (problem != null) {
                    unconverted.add(new Unconverted(request, problem));
                    continue;
                }
                String body = request.requestBody == null || request.requestBody.isBlank()
                        ? null : request.requestBody;
                boolean authObserved = request.requestHeaders.keySet().stream()
                        .anyMatch(name -> "authorization".equalsIgnoreCase(name));
                definitions.add(ApiDefinition.of(request.method, request.host, request.path, request.query,
                        request.status, ApiDefinition.Source.API_MONITOR, authObserved, body));
                extraHeaders.add(replayHeaders(request.requestHeaders));
                schemes.add(request.scheme);
            }

            String defaultHost = definitions.isEmpty() ? "" : definitions.get(0).host();
            String defaultDomain = defaultHost.contains(":")
                    ? defaultHost.substring(0, defaultHost.indexOf(':')) : defaultHost;
            int defaultPort = defaultHost.contains(":")
                    ? parsePortSafe(defaultHost.substring(defaultHost.indexOf(':') + 1)) : 80;

            // No REST bearer token exists here (a soak run logs in through the UI): ${accessToken}
            // starts empty and is filled by the login sampler's own JSON Extractor on replay.
            // ${loginEmail}/${loginPassword} come from the same username/password config keys
            // ApiAuthClient already uses - the captured login body's password was masked on capture.
            String jmx = LoadTestPlanBuilder.buildOrdered(planName, defaultDomain, defaultPort, "",
                    definitions, extraHeaders, schemes,
                    ConfigReader.getOrDefault("username", ""), ConfigReader.getOrDefault("password", ""),
                    LoadProfile.fromConfig());

            Path jmxDirectory = soakRunDirectory.resolve("jmx");
            Files.createDirectories(jmxDirectory);
            Path destination = jmxDirectory.resolve(JMX_FILE_NAME);
            Files.writeString(destination, jmx, StandardCharsets.UTF_8);

            LoadTestJmxValidator.ValidationResult validation = LoadTestJmxValidator.validate(destination);
            int samplers = validation.httpSamplerCount;
            int expected = definitions.size();
            int mismatch = captured.size() - samplers;

            String summary = buildSummary(captured.size(), methodCounts, other, expected, samplers, mismatch,
                    unconverted, validation, destination);
            Files.writeString(jmxDirectory.resolve("soak-api-summary.txt"), summary, StandardCharsets.UTF_8);
            System.out.println(summary);
            return destination;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[SOAK JMX] Could not generate this run's JMX file: " + exception.getMessage());
            return null;
        }
    }

    /** {@code null} when convertible; otherwise the exact reason it is not. */
    private static String conversionProblem(CapturedRequest request) {
        if (request.method == null || !request.method.matches("[A-Z]+")) {
            return "HTTP method is not a valid token: '" + request.method + "'";
        }
        if (request.host == null || request.host.isBlank()) {
            return "captured URL has no host";
        }
        if (request.path == null || !request.path.startsWith("/")) {
            return "captured URL has no absolute path";
        }
        return null;
    }

    private static Map<String, String> replayHeaders(Map<String, String> captured) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : captured.entrySet()) {
            String name = header.getKey() == null ? "" : header.getKey().toLowerCase(Locale.ROOT);
            String value = header.getValue();
            if (name.isBlank() || name.startsWith(":") || name.startsWith("sec-") || SKIPPED_HEADERS.contains(name)
                    || value == null || value.isBlank() || value.matches("\\*+")) {
                continue;
            }
            result.put(header.getKey(), value);
        }
        return result;
    }

    private static String buildSummary(int capturedTotal, Map<String, Integer> methodCounts, int other,
                                       int convertible, int samplers, int mismatch, List<Unconverted> unconverted,
                                       LoadTestJmxValidator.ValidationResult validation, Path jmx) {
        StringBuilder out = new StringBuilder();
        out.append("\n========== SOAK TEST API SUMMARY ==========\n\n");
        out.append("Total APIs Captured : ").append(capturedTotal).append(" (every occurrence, duplicates kept)\n\n");
        for (Map.Entry<String, Integer> entry : methodCounts.entrySet()) {
            out.append(String.format("%-7s: %d%n", entry.getKey(), entry.getValue()));
        }
        out.append(String.format("%-7s: %d%n%n", "OTHER", other));
        out.append("Total Captured : ").append(capturedTotal).append('\n');
        out.append("Total Added to JMX : ").append(convertible).append('\n');
        out.append("Total Executed : NOT EXECUTED - this step only generates the JMX; it never replays "
                + "captured writes/deletes against the live app. Run the JMX in JMeter to execute.\n");
        out.append("Passed : N/A\nFailed : N/A\n");
        out.append("Skipped : ").append(unconverted.size()).append(" (could not be converted)\n");
        out.append("Dropped at capture (cap reached / capture error) : ")
                .append(ApiMonitor.getCapturedRequestsDropped()).append("\n\n");
        out.append("JMX File:\n").append(jmx.toString().replace('\\', '/')).append("\n\n");
        out.append("JMX valid XML / structure : ").append(validation.isValid() ? "YES" : "NO").append('\n');
        out.append("JMX Samplers : ").append(samplers).append('\n');
        out.append("Captured APIs : ").append(capturedTotal).append('\n');
        out.append("Mismatch : ").append(mismatch).append('\n');
        if (!unconverted.isEmpty()) {
            out.append("\nAPIs that could not be converted to a sampler:\n");
            for (Unconverted item : unconverted) {
                out.append("  #").append(item.request.sequence).append(' ').append(item.request.method).append(' ')
                        .append(item.request.host).append(item.request.path).append(" - ").append(item.reason)
                        .append('\n');
            }
        }
        if (mismatch != unconverted.size()) {
            out.append("\nWARNING: JMX sampler count differs from (captured - unconverted) - investigate.\n");
        }
        out.append("============================================\n");
        return out.toString();
    }

    private static int parsePortSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 80;
        }
    }
}
