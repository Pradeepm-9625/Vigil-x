package com.vigilx.apisecurity.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a JMeter {@code .jtl} (CSV) results file into the summary numbers Step 11 of the brief asks
 * for: response time distribution, throughput, and error rate. Reads the header line rather than
 * assuming a fixed column order, since that can vary slightly across JMeter versions/configurations.
 */
public final class JtlResultParser {

    public static final class Summary {
        public int totalSamples;
        public int errorCount;
        public double errorRatePercent;
        public double minMs;
        public double maxMs;
        public double avgMs;
        public double medianMs;
        public double p90Ms;
        public double p95Ms;
        public double p99Ms;
        public double throughputPerSecond;
        public final Map<String, LabelSummary> byLabel = new LinkedHashMap<>();
    }

    public static final class LabelSummary {
        public int samples;
        public int errors;
        public double avgMs;
        public double p95Ms;
    }

    private JtlResultParser() {
    }

    public static Summary parse(Path jtlFile) throws IOException {
        Summary summary = new Summary();
        if (!Files.exists(jtlFile)) {
            return summary;
        }

        List<String> lines = Files.readAllLines(jtlFile);
        if (lines.isEmpty()) {
            return summary;
        }

        String[] header = lines.get(0).split(",");
        int elapsedIdx = indexOf(header, "elapsed");
        int successIdx = indexOf(header, "success");
        int labelIdx = indexOf(header, "label");
        int timestampIdx = indexOf(header, "timeStamp");
        if (elapsedIdx < 0 || successIdx < 0) {
            return summary;
        }

        List<Double> allTimes = new ArrayList<>();
        Map<String, List<Double>> timesByLabel = new LinkedHashMap<>();
        Map<String, Integer> errorsByLabel = new LinkedHashMap<>();
        long minTimestamp = Long.MAX_VALUE;
        long maxTimestamp = Long.MIN_VALUE;

        for (int i = 1; i < lines.size(); i++) {
            String[] fields = splitCsvLine(lines.get(i));
            if (fields.length <= elapsedIdx) {
                continue;
            }
            double elapsed;
            try {
                elapsed = Double.parseDouble(fields[elapsedIdx]);
            } catch (NumberFormatException exception) {
                continue;
            }
            boolean success = successIdx < fields.length && Boolean.parseBoolean(fields[successIdx]);
            String label = labelIdx >= 0 && labelIdx < fields.length ? fields[labelIdx] : "(unknown)";

            allTimes.add(elapsed);
            timesByLabel.computeIfAbsent(label, key -> new ArrayList<>()).add(elapsed);
            summary.totalSamples++;
            if (!success) {
                summary.errorCount++;
                errorsByLabel.merge(label, 1, Integer::sum);
            }

            if (timestampIdx >= 0 && timestampIdx < fields.length) {
                try {
                    long timestamp = Long.parseLong(fields[timestampIdx]);
                    minTimestamp = Math.min(minTimestamp, timestamp);
                    maxTimestamp = Math.max(maxTimestamp, timestamp);
                } catch (NumberFormatException ignored) {
                    // Throughput just stays 0 if timestamps are unavailable.
                }
            }
        }

        if (summary.totalSamples == 0) {
            return summary;
        }

        Collections.sort(allTimes);
        summary.errorRatePercent = 100.0 * summary.errorCount / summary.totalSamples;
        summary.minMs = allTimes.get(0);
        summary.maxMs = allTimes.get(allTimes.size() - 1);
        summary.avgMs = allTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        summary.medianMs = percentile(allTimes, 50);
        summary.p90Ms = percentile(allTimes, 90);
        summary.p95Ms = percentile(allTimes, 95);
        summary.p99Ms = percentile(allTimes, 99);

        if (maxTimestamp > minTimestamp) {
            double seconds = (maxTimestamp - minTimestamp) / 1000.0;
            summary.throughputPerSecond = seconds > 0 ? summary.totalSamples / seconds : 0;
        }

        for (Map.Entry<String, List<Double>> entry : timesByLabel.entrySet()) {
            List<Double> times = entry.getValue();
            Collections.sort(times);
            LabelSummary labelSummary = new LabelSummary();
            labelSummary.samples = times.size();
            labelSummary.errors = errorsByLabel.getOrDefault(entry.getKey(), 0);
            labelSummary.avgMs = times.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            labelSummary.p95Ms = percentile(times, 95);
            summary.byLabel.put(entry.getKey(), labelSummary);
        }

        return summary;
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil((percentile / 100.0) * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Minimal CSV split good enough for JMeter's own writer (quotes only around fields with commas). */
    private static String[] splitCsvLine(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }
}
