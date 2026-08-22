package com.vigilx.apisecurity.reporting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One timestamped subfolder per execution - mirrors the existing SOAK convention
 * ({@code target/soak-test/run-<timestamp>/}) - so running any apisecurity test class multiple times
 * never overwrites a previous run's reports: every invocation gets its own folder under the same
 * configured reports root, e.g. {@code target/apisecurity/reports/run-20260822-193045/}.
 *
 * <p>The timestamp is computed once, lazily, on first use within this JVM: every test class run in
 * the same {@code mvn test} invocation (one Surefire fork - e.g. {@code -Dtest=A,B,C}, or the single
 * {@code FullSecurityExecutionTest} orchestrator) shares the same run folder, exactly as if they were
 * one execution; a separate {@code mvn test} invocation gets a fresh one.
 *
 * <p>A {@code latest-run.txt} pointer file at the reports root always names the most recently started
 * run, so a report meant to summarize a prior run (see {@link #latestRun}) - e.g. the standalone,
 * separately-invoked consolidated report - can find it without needing to know the timestamp.
 */
public final class ApiSecurityRunContext {

    private static final DateTimeFormatter FOLDER_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String POINTER_FILE = "latest-run.txt";

    private static volatile String cachedRunFolderName;

    private ApiSecurityRunContext() {
    }

    /**
     * The folder this execution writes its reports into: {@code <root>/run-<timestamp>/}. Creates
     * (or reuses, within this same JVM) one timestamp and records it as the latest run.
     */
    public static Path startNewRun(Path root) {
        Path runDirectory = root.resolve(runFolderName());
        writeLatestPointer(root, runFolderName());
        return runDirectory;
    }

    /**
     * For a report that summarizes a prior run rather than starting its own - e.g. a consolidated
     * report invoked separately, after the others already ran. Resolves to whatever
     * {@code latest-run.txt} names; falls back to {@code root} itself (the old flat layout) if no
     * run has ever been recorded yet, so this never fails on a first-ever execution.
     */
    public static Path latestRun(Path root) {
        Path pointerFile = root.resolve(POINTER_FILE);
        try {
            if (Files.exists(pointerFile)) {
                String folder = Files.readString(pointerFile).strip();
                if (!folder.isBlank()) {
                    return root.resolve(folder);
                }
            }
        } catch (IOException exception) {
            System.err.println("[API SECURITY RUN CONTEXT] Could not read " + pointerFile + ": "
                    + exception.getMessage());
        }
        return root;
    }

    private static String runFolderName() {
        String name = cachedRunFolderName;
        if (name == null) {
            synchronized (ApiSecurityRunContext.class) {
                name = cachedRunFolderName;
                if (name == null) {
                    name = "run-" + LocalDateTime.now().format(FOLDER_TIMESTAMP);
                    cachedRunFolderName = name;
                }
            }
        }
        return name;
    }

    private static void writeLatestPointer(Path root, String folderName) {
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(POINTER_FILE), folderName);
        } catch (IOException exception) {
            System.err.println("[API SECURITY RUN CONTEXT] Could not write " + POINTER_FILE + ": "
                    + exception.getMessage());
        }
    }
}
