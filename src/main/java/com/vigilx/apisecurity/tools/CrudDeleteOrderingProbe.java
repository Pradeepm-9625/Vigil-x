package com.vigilx.apisecurity.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.performance.LoadProfile;
import com.vigilx.apisecurity.performance.LoadTestPlanBuilder;

/**
 * Throwaway probe: confirms, without a live login or a JMeter run, that {@link LoadTestPlanBuilder#build}
 * now emits every CRUD lifecycle's DELETE sampler AFTER every standalone sampler - the fix made so a
 * standalone sampler referencing a resource never 404s because an earlier lifecycle's DELETE already
 * removed it mid-iteration. Two CRUD groups (resourceA, resourceB) plus one standalone GET are built;
 * the generated XML's own sampler testnames are inspected in document order to verify both DELETE
 * samplers land after the standalone one. Never touches a browser or the live application.
 */
public final class CrudDeleteOrderingProbe {
    private CrudDeleteOrderingProbe() {
    }

    public static void main(String[] args) throws Exception {
        List<ApiDefinition> apis = List.of(
                ApiDefinition.of("POST", "example.test:80", "/api/v1/resourcea", null, 201,
                        ApiDefinition.Source.API_MONITOR, true, "{\"name\":\"a\"}"),
                ApiDefinition.of("PUT", "example.test:80", "/api/v1/resourcea/{id}", null, 200,
                        ApiDefinition.Source.API_MONITOR, true, "{\"name\":\"a2\"}"),
                ApiDefinition.of("DELETE", "example.test:80", "/api/v1/resourcea/{id}", null, 204,
                        ApiDefinition.Source.API_MONITOR, true, null),
                ApiDefinition.of("GET", "example.test:80", "/api/v1/standalone", null, 200,
                        ApiDefinition.Source.API_MONITOR, true, null),
                ApiDefinition.of("POST", "example.test:80", "/api/v1/resourceb", null, 201,
                        ApiDefinition.Source.API_MONITOR, true, "{\"name\":\"b\"}"),
                ApiDefinition.of("DELETE", "example.test:80", "/api/v1/resourceb/{id}", null, 204,
                        ApiDefinition.Source.API_MONITOR, true, null));

        String jmx = LoadTestPlanBuilder.build("CRUD Delete Ordering Probe", "example.test", 80, "token",
                apis, LoadProfile.fromConfig());

        Path out = Path.of("target/apisecurity/jmeter-run/crud-delete-ordering-probe.jmx");
        Files.createDirectories(out.getParent());
        Files.writeString(out, jmx, StandardCharsets.UTF_8);
        System.out.println("PROBE: wrote " + out.toAbsolutePath());

        Matcher m = Pattern.compile("testname=\"((?:GET|POST|PUT|PATCH|DELETE) [^\"]*)\"").matcher(jmx);
        List<String> order = new java.util.ArrayList<>();
        while (m.find()) {
            order.add(m.group(1));
        }
        System.out.println("PROBE: sampler order = " + order);

        int standaloneIndex = indexOfContaining(order, "GET /api/v1/standalone");
        int deleteAIndex = indexOfContaining(order, "DELETE /api/v1/resourcea");
        int deleteBIndex = indexOfContaining(order, "DELETE /api/v1/resourceb");
        boolean pass = standaloneIndex >= 0 && deleteAIndex > standaloneIndex && deleteBIndex > standaloneIndex;
        System.out.println("PROBE: standalone@" + standaloneIndex + " deleteA@" + deleteAIndex
                + " deleteB@" + deleteBIndex);
        System.out.println("PROBE: RESULT = " + (pass ? "PASS" : "FAIL")
                + " (both DELETE samplers land after the standalone sampler)");
    }

    private static int indexOfContaining(List<String> list, String needle) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
