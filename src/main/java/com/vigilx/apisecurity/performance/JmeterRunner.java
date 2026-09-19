package com.vigilx.apisecurity.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.vigilx.config.ConfigReader;

/**
 * Launches Apache JMeter in non-GUI (CLI) mode against a generated {@code .jmx} plan and waits for
 * it to finish, bounded by a hard timeout so a hung JMeter process cannot block the build forever.
 * Reuses the JMeter installation already present on this machine - never bundles or downloads one.
 */
public final class JmeterRunner {

    public static final class RunResult {
        public boolean executed;
        public String error;
        public Path jtlFile;
        public Path htmlReportDir;
        public Path jmxFile;
    }

    public RunResult run(String scenarioName, String jmxContent, LoadProfile profile) {
        RunResult result = new RunResult();
        try {
            Path performanceDir = Paths.get(ConfigReader.getOrDefault(
                    "apisecurity.performance.directory", "target/apisecurity/performance"));
            Files.createDirectories(performanceDir);

            result.jmxFile = performanceDir.resolve(scenarioName + "-plan.jmx");
            Files.writeString(result.jmxFile, jmxContent, StandardCharsets.UTF_8);

            result.jtlFile = performanceDir.resolve(scenarioName + "-results.jtl");
            Files.deleteIfExists(result.jtlFile);

            result.htmlReportDir = performanceDir.resolve(scenarioName + "-html-report");
            deleteRecursively(result.htmlReportDir);

            Optional<Path> jmeterBin = findJmeterExecutable();
            if (jmeterBin.isEmpty()) {
                result.error = "No JMeter executable found under jmeter.home ("
                        + ConfigReader.getOrDefault("jmeter.home", "C:/Program Files/apache-jmeter-5.6.3") + ")";
                return result;
            }

            ProcessBuilder builder = new ProcessBuilder(
                    jmeterBin.get().toString(), "-n",
                    "-t", result.jmxFile.toAbsolutePath().toString(),
                    "-l", result.jtlFile.toAbsolutePath().toString(),
                    "-e", "-o", result.htmlReportDir.toAbsolutePath().toString())
                    .redirectOutput(performanceDir.resolve(scenarioName + "-jmeter.log").toFile())
                    .redirectErrorStream(true);

            System.out.println("[JMETER] Starting: " + String.join(" ", builder.command()));
            Process process = builder.start();

            long timeoutSeconds = profile.rampUpSeconds() + profile.durationSeconds() + 300L;
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.error = "JMeter did not finish within " + timeoutSeconds + "s - process killed.";
                return result;
            }

            if (process.exitValue() != 0) {
                result.error = "JMeter exited with code " + process.exitValue() + " - see "
                        + performanceDir.resolve(scenarioName + "-jmeter.log");
                return result;
            }

            result.executed = true;
            return result;

        } catch (Exception exception) {
            result.error = "Could not run JMeter: " + exception.getMessage();
            return result;
        }
    }

    private Optional<Path> findJmeterExecutable() {
        Path home = Paths.get(ConfigReader.getOrDefault("jmeter.home", "C:/Program Files/apache-jmeter-5.6.3"));
        Path bat = home.resolve("bin/jmeter.bat");
        Path sh = home.resolve("bin/jmeter");
        if (Files.isRegularFile(bat)) {
            return Optional.of(bat);
        }
        if (Files.isRegularFile(sh)) {
            return Optional.of(sh);
        }
        return Optional.empty();
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup; a leftover file here does not affect correctness.
                }
            });
        }
    }
}
