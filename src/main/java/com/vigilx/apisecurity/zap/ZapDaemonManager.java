package com.vigilx.apisecurity.zap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import com.vigilx.config.ConfigReader;

/**
 * Starts a local OWASP ZAP daemon (headless, API-only - no GUI) if one is not already listening,
 * and stops only the instance this class itself started. Never touches an already-running ZAP -
 * someone may be using it interactively.
 */
public final class ZapDaemonManager {

    private final ZapClient client;
    private final String address;
    private final int port;
    private final String apiKey;
    private final Path zapHome;
    private final long startupTimeoutMs;

    private Process startedProcess;

    public ZapDaemonManager() {
        this.address = ConfigReader.getOrDefault("zap.address", "localhost");
        this.port = intConfig("zap.port", 8080);
        this.apiKey = ConfigReader.getOrDefault("zap.api.key", "");
        this.zapHome = Paths.get(ConfigReader.getOrDefault("zap.home", "C:/Program Files/ZAP/Zed Attack Proxy"));
        this.startupTimeoutMs = longConfig("zap.daemon.startup.timeout.ms", 60000);
        this.client = new ZapClient(address, port, apiKey);
    }

    public ZapClient client() {
        return client;
    }

    /** @return true if ZAP is up (whether it was already running or this call started it) */
    public boolean ensureRunning() {
        if (client.isRunning()) {
            System.out.println("[ZAP] Already running at " + address + ":" + port + " - reusing it.");
            return true;
        }

        if (!Boolean.parseBoolean(ConfigReader.getOrDefault("zap.daemon.autostart", "true"))) {
            System.err.println("[ZAP] Not running and zap.daemon.autostart=false - not starting it.");
            return false;
        }

        Optional<Path> jar = findZapJar();
        if (jar.isEmpty()) {
            System.err.println("[ZAP] No zap-*.jar found under " + zapHome + " - cannot auto-start.");
            return false;
        }

        try {
            System.out.println("[ZAP] Starting daemon: " + jar.get());
            ProcessBuilder builder = new ProcessBuilder("java", "-Xmx512m", "-jar", jar.get().toString(),
                    "-daemon", "-host", address, "-port", String.valueOf(port),
                    "-config", "api.key=" + apiKey, "-config", "api.disablekey=false")
                    .directory(zapHome.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()))
                    .redirectErrorStream(true);
            startedProcess = builder.start();
        } catch (IOException exception) {
            System.err.println("[ZAP] Could not start the daemon: " + exception.getMessage());
            return false;
        }

        long deadline = System.currentTimeMillis() + startupTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.isRunning()) {
                System.out.println("[ZAP] Daemon is up (" + client.version() + ").");
                return true;
            }
            sleep(2000);
        }
        System.err.println("[ZAP] Daemon did not become ready within " + startupTimeoutMs + "ms.");
        return false;
    }

    /** Stops the daemon only if this manager is the one that started it. */
    public void stopIfStartedByUs() {
        if (startedProcess == null) {
            return;
        }
        try {
            client.shutdown();
        } finally {
            startedProcess.destroy();
            startedProcess = null;
        }
    }

    private Optional<Path> findZapJar() {
        if (!Files.isDirectory(zapHome)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(zapHome)) {
            return files.filter(p -> p.getFileName().toString().matches("zap-.*\\.jar"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private File logFile() {
        try {
            Path directory = Paths.get(ConfigReader.getOrDefault("apisecurity.reports.directory",
                    "target/apisecurity/reports"));
            Files.createDirectories(directory);
            return directory.resolve("zap-daemon.log").toFile();
        } catch (IOException exception) {
            return new File("zap-daemon.log");
        }
    }

    private static void sleep(long millis) {
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
