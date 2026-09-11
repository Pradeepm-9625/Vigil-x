package com.vigilx.apisecurity.zap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vigilx.apisecurity.execution.ApiAuthClient;
import com.vigilx.apisecurity.execution.ApiExecutionPolicy;
import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.config.ConfigReader;

/**
 * Drives one ZAP scan of the final combined API inventory.
 *
 * <p>Flow: start/reuse the local ZAP daemon -&gt; authenticate once and inject the Bearer token via a
 * Replacer rule (ZAP's built-in cookie/form auth methods do not fit this app's Bearer-JWT scheme)
 * -&gt; feed every eligible GET URL through ZAP via {@code accessUrl} (this is what triggers ZAP's
 * passive scanner - no attack traffic yet) -&gt; wait for the passive scan queue to drain -&gt;
 * <strong>only if explicitly enabled</strong> ({@code zap.active.scan.enabled}, off by default), run
 * a targeted active scan against each individual URL, never a full-site spider+scan.
 *
 * <p>Same GET-only policy as REST Assured/OWASP checks: write endpoints are never fed to ZAP as
 * scan targets, active or passive, since ZAP's active scanner sends real attack payloads that could
 * trigger a real mutation if it ever reached a write endpoint.
 */
public final class ZapScanRunner {

    private final ApiExecutionPolicy policy = ApiExecutionPolicy.loadDefault();
    private final String scheme;

    public ZapScanRunner() {
        this.scheme = ConfigReader.getOrDefault("apisecurity.restassured.scheme", "http");
    }

    public static final class RunResult {
        public final List<Map<String, Object>> alerts = new ArrayList<>();
        public boolean daemonAvailable;
        public boolean authFailed;
        public String authFailureReason;
        public int urlsFedToPassiveScan;
        public boolean activeScanEnabled;
        public int urlsActivelyScanned;
    }

    public RunResult run(List<ApiDefinition> combinedInventory) {
        RunResult result = new RunResult();
        ZapDaemonManager daemon = new ZapDaemonManager();

        // Everything from here down is inside try/finally, deliberately including ensureRunning():
        // it may have already launched a daemon process before timing out waiting for it to become
        // ready, and that process must still be cleaned up rather than left as an orphan.
        try {
            result.daemonAvailable = daemon.ensureRunning();
            if (!result.daemonAvailable) {
                return result;
            }

            List<ApiDefinition> eligible = combinedInventory.stream()
                    .filter(policy::isEligibleForFunctionalTest)
                    .toList();
            if (eligible.isEmpty()) {
                System.out.println("[ZAP] No eligible (GET) APIs to scan.");
                return result;
            }

            String authHost = resolveAuthHost(combinedInventory, eligible);
            String token;
            try {
                token = ApiAuthClient.login(scheme, authHost);
            } catch (Exception exception) {
                result.authFailed = true;
                result.authFailureReason = exception.getMessage();
                System.err.println("[ZAP] Aborting: authentication failed - " + exception.getMessage());
                return result;
            }

            ZapClient client = daemon.client();
            client.setHeaderReplacerRule("Authorization", "Bearer " + token);

            long delayMs = longConfig("apisecurity.restassured.delay.ms", 300);
            Set<String> hosts = new LinkedHashSet<>();
            for (ApiDefinition definition : eligible) {
                String url = scheme + "://" + definition.host() + definition.samplePath()
                        + (definition.sampleQuery() == null || definition.sampleQuery().isBlank()
                                ? "" : "?" + definition.sampleQuery());
                client.accessUrl(url);
                hosts.add(scheme + "://" + definition.host());
                result.urlsFedToPassiveScan++;
                sleep(delayMs);
            }

            waitForPassiveScanToDrain(client, longConfig("zap.passive.scan.timeout.ms", 30000));

            result.activeScanEnabled = Boolean.parseBoolean(ConfigReader.getOrDefault("zap.active.scan.enabled", "false"));
            if (result.activeScanEnabled) {
                runActiveScans(client, eligible, result);
            } else {
                System.out.println("[ZAP] Active scan disabled (zap.active.scan.enabled=false) - "
                        + "passive-scan findings only. This is the default, safety-first posture.");
            }

            for (String host : hosts) {
                result.alerts.addAll(client.alerts(host));
            }

        } finally {
            daemon.stopIfStartedByUs();
        }

        return result;
    }

    private void runActiveScans(ZapClient client, List<ApiDefinition> eligible, RunResult result) {
        int maxUrls = intConfig("zap.active.scan.max.urls", 10);
        long perUrlTimeoutMs = longConfig("zap.active.scan.per.url.timeout.ms", 60000);

        List<ApiDefinition> targets = eligible.size() > maxUrls ? eligible.subList(0, maxUrls) : eligible;
        System.out.println("[ZAP] Active scan enabled - scanning " + targets.size() + " of "
                + eligible.size() + " eligible URLs (zap.active.scan.max.urls=" + maxUrls + ").");

        for (ApiDefinition definition : targets) {
            String url = scheme + "://" + definition.host() + definition.samplePath()
                    + (definition.sampleQuery() == null || definition.sampleQuery().isBlank()
                            ? "" : "?" + definition.sampleQuery());
            try {
                String scanId = client.activeScan(url);
                long deadline = System.currentTimeMillis() + perUrlTimeoutMs;
                int progress = 0;
                while (progress < 100 && System.currentTimeMillis() < deadline) {
                    sleep(2000);
                    progress = client.activeScanProgress(scanId);
                }
                result.urlsActivelyScanned++;
            } catch (Exception exception) {
                System.err.println("[ZAP] Active scan failed for " + url + ": " + exception.getMessage());
            }
        }
    }

    private void waitForPassiveScanToDrain(ZapClient client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int remaining = client.recordsToScan();
            if (remaining == 0) {
                System.out.println("[ZAP] Passive scan queue drained.");
                return;
            }
            sleep(1000);
        }
        System.out.println("[ZAP] Passive scan queue did not fully drain within " + timeoutMs
                + "ms - reporting whatever alerts exist so far.");
    }

    private static String resolveAuthHost(List<ApiDefinition> combinedInventory, List<ApiDefinition> eligible) {
        String configured = ConfigReader.getOrDefault("apisecurity.auth.host", "");
        if (!configured.isBlank()) {
            return configured;
        }
        for (ApiDefinition definition : combinedInventory) {
            if ("/auth/login".equals(definition.normalizedPath())) {
                return definition.host();
            }
        }
        return eligible.get(0).host();
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static int intConfig(String key, int fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static long longConfig(String key, long fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }
}
