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
    public String overall = "FAIL";
    public String error;
    public String screenshot;
    public String trace;
    /** Relative path to this execution's consolidated API failure document, when one was written. */
    public String apiFailureLog;
    public Map<String, String> pageResults = new LinkedHashMap<>();
    public List<String> apiFailures = new ArrayList<>();
    public List<String> streamFailures = new ArrayList<>();
}
