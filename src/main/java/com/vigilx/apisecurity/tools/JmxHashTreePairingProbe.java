package com.vigilx.apisecurity.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.performance.LoadProfile;
import com.vigilx.apisecurity.performance.LoadTestPlanBuilder;

/**
 * Throwaway probe: reproduces, in isolation, the two {@code buildOrdered()} input shapes that were
 * found (via a real soak run's own generated JMX refusing to load in JMeter with "HeaderManager
 * cannot be cast to HashTree") to leave an orphan {@code <hashTree/>} with no preceding element -
 * (1) a definition with no real captured status ({@code sampleStatus() <= 0}), and (2) a multipart
 * upload body - without needing a full ~20 minute soak run to reproduce them. Writes the resulting
 * plan to {@code target/apisecurity/jmeter-run/hashtree-fix-probe.jmx} for a real {@code jmeter -n -t}
 * load check. Never touches a browser or the live application.
 */
public final class JmxHashTreePairingProbe {
    private JmxHashTreePairingProbe() {
    }

    public static void main(String[] args) throws Exception {
        List<ApiDefinition> definitions = new ArrayList<>();
        List<Map<String, String>> extraHeaders = new ArrayList<>();
        List<String> schemes = new ArrayList<>();

        // #1: a normal GET with a real captured status - the ordinary, already-working case.
        definitions.add(ApiDefinition.of("GET", "example.test:80", "/api/v1/health", null, 200,
                ApiDefinition.Source.API_MONITOR, true, null));
        extraHeaders.add(Map.of());
        schemes.add("http");

        // #2: sampleStatus() <= 0 - appendResponseAssertion() writes nothing for this one.
        definitions.add(ApiDefinition.of("GET", "example.test:80", "/api/v1/never-observed", null, 0,
                ApiDefinition.Source.API_MONITOR, true, null));
        extraHeaders.add(Map.of());
        schemes.add("http");

        // #3: a multipart upload - appendOrderedSampler() skips appendResponseAssertion() entirely
        // for this one (see its own "never asserted on" comment).
        definitions.add(ApiDefinition.of("POST", "example.test:80", "/api/v1/upload", null, 201,
                ApiDefinition.Source.API_MONITOR, true,
                "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n<bytes>\r\n--boundary--\r\n"));
        Map<String, String> multipartHeaders = new LinkedHashMap<>();
        multipartHeaders.put("content-type", "multipart/form-data; boundary=oldBoundary");
        extraHeaders.add(multipartHeaders);
        schemes.add("http");

        // #4: another real-status GET right after the two problem cases, to prove the plan recovers
        // (no lingering misalignment carried forward into later samplers).
        definitions.add(ApiDefinition.of("GET", "example.test:80", "/api/v1/after", null, 200,
                ApiDefinition.Source.API_MONITOR, true, null));
        extraHeaders.add(Map.of());
        schemes.add("http");

        String jmx = LoadTestPlanBuilder.buildOrdered("HashTree Pairing Probe", "example.test", 80, "",
                definitions, extraHeaders, schemes, "user@example.test", "pw", "devicepw",
                LoadProfile.fromConfig());

        Path out = Path.of("target/apisecurity/jmeter-run/hashtree-fix-probe.jmx");
        Files.createDirectories(out.getParent());
        Files.writeString(out, jmx, StandardCharsets.UTF_8);
        System.out.println("PROBE: wrote " + out.toAbsolutePath());
        System.out.println("PROBE: run `jmeter -n -t " + out + " -l <out>.jtl` to confirm it loads.");
    }
}
