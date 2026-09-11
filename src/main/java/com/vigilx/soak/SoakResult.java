package com.vigilx.soak;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine-readable result of a single isolated health-check execution. */
public class SoakResult {
    public String executionId;
    public String timestamp = Instant.now().toString();
    public long durationMs;
    public String login = "SKIPPED";
    public String dashboard = "SKIPPED";
    public String liveView = "SKIPPED";
    public String camera;
    public String stream = "SKIPPED";
    public Long streamStartupTimeMs;
    public String playback = "SKIPPED";
    public Long playbackStartupTimeMs;
    public String alerts = "SKIPPED";
    public String logout = "SKIPPED";
    /** The application's health as this run found it: PASS, or FAIL if any validation failed. */
    public String overall = "FAIL";
    /**
     * Whether the automation itself ran to a normal end: "COMPLETED" (default) once every configured
     * validation was attempted, logout ran, the browser closed and reports were written - regardless
     * of {@link #overall}. Only set to "ERROR" for a genuine infrastructure/framework problem the run
     * could not isolate (browser crash, Playwright init failure, an unexpected bug, ...), as opposed
     * to the application itself being unhealthy. See {@link #executionError} for that case's detail.
     */
    public String executionStatus = "COMPLETED";
    /** Set only when {@link #executionStatus} is "ERROR": the infrastructure exception's message. */
    public String executionError;
    public String error;
    public String screenshot;
    public String trace;
    /** Relative path to this execution's consolidated API failure document, when one was written. */
    public String apiFailureLog;
    public Map<String, String> pageResults = new LinkedHashMap<>();
    public List<String> apiFailures = new ArrayList<>();
    public List<String> streamFailures = new ArrayList<>();
}
