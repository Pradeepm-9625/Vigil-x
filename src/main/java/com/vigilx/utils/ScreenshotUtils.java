package com.vigilx.utils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

import com.microsoft.playwright.Page;
import com.vigilx.reporting.SoakRunContext;

public final class ScreenshotUtils {

    private static final String SCREENSHOT_FOLDER = "screenshots";

    /**
     * The most recent screenshot path any capture method on this thread has taken, so a centralized
     * failure listener (e.g. {@link com.vigilx.monitoring.PageApiTracker}) can tell whether a page's
     * own failure handling already captured evidence for the current check before taking a second,
     * duplicate one. Set by every capture method here; read-and-cleared via
     * {@link #consumeLastCapturedScreenshot()}.
     */
    private static final ThreadLocal<String> LAST_CAPTURED = new ThreadLocal<>();

    /** Guarantees a unique filename even when two failures happen within the same second. */
    private static final AtomicLong FAILURE_SEQUENCE = new AtomicLong();

    private ScreenshotUtils() {
        // Prevent instantiation
    }

    public static String capture(Page page, String testName) {

        try {

            Files.createDirectories(Paths.get(SCREENSHOT_FOLDER));

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            String fileName = testName + "_" + timestamp + ".png";

            Path screenshotPath = Paths.get(SCREENSHOT_FOLDER, fileName);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));

            return screenshotPath.toString();

        } catch (IOException e) {
            throw new RuntimeException("Unable to capture screenshot.", e);
        }
    }

    /**
     * Captures a timestamped full-page screenshot into the supplied directory, creating it when
     * missing. Unlike {@link #capture(Page, String)} this never throws, so a soak validation can
     * record evidence for a failure without the capture itself ending the run.
     *
     * @return the written path, or {@code null} when the screenshot could not be taken
     */
    public static String captureTo(Page page, Path directory, String name) {

        try {

            Files.createDirectories(directory);

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

            Path screenshotPath = directory.resolve(name + "-" + timestamp + ".png");

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));

            System.out.println("[SCREENSHOT] Saved to " + screenshotPath);

            LAST_CAPTURED.set(screenshotPath.toString());
            return screenshotPath.toString();

        } catch (Exception exception) {
            System.err.println("[SCREENSHOT FAILURE] Could not save " + name + ": " + exception.getMessage());
            return null;
        }
    }

    /**
     * Returns the most recent screenshot path captured on this thread (by {@link #captureTo} or
     * {@link #captureFailure}) since the last call to this method, then clears it - so a caller can
     * tell "did the check I just ran already capture its own failure evidence?" without every
     * existing {@code fail()} method needing to change. {@code null} when nothing was captured.
     */
    public static String consumeLastCapturedScreenshot() {
        String value = LAST_CAPTURED.get();
        LAST_CAPTURED.remove();
        return value;
    }

    /** Clears any leftover marker from a previous check, so it is never mistaken for this one's. */
    public static void clearLastCapturedScreenshot() {
        LAST_CAPTURED.remove();
    }

    /**
     * Centralized Soak Test failure capture: takes a full-page screenshot into the current soak
     * run's own {@code screenshots/<Page>/<Tab>/} folder (via {@link SoakRunContext}) and writes a
     * companion failure log next to it under {@code logs/<Page>/<Tab>/} - the single place new and
     * existing soak validations should both funnel through instead of each rolling its own
     * screenshot directory and never recording a failure log at all.
     *
     * <p>Never throws and never overwrites an earlier failure: the filename carries a timestamp,
     * the sanitized step name and a monotonically increasing sequence number.
     *
     * @param page           the page to screenshot
     * @param stepName       the same name passed to {@code validatePage(...)} (e.g.
     *                       {@code "Users & Roles - Create User"}), used to resolve the Page/Tab
     *                       folder and recorded in the log
     * @param testContext    the owning TestNG test/method (e.g.
     *                       {@code "SoakHealthCheckTest.runConfiguredHealthCheck"}), or {@code null}
     * @param failureMessage a short human-readable failure reason
     * @param throwable      the causing exception, or {@code null} when the failure was a plain
     *                       assertion (no exception escaped)
     * @return the written screenshot path, or {@code null} when capture itself failed
     */
    public static String captureFailure(Page page, String stepName, String testContext,
                                        String failureMessage, Throwable throwable) {
        try {
            SoakRunContext run = SoakRunContext.current();
            Path screenshotDirectory = run.screenshotDirectory(stepName);
            Path logDirectory = run.logDirectory(stepName);
            Files.createDirectories(screenshotDirectory);
            Files.createDirectories(logDirectory);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String baseName = timestamp + "_" + sanitize(stepName) + "_failure_"
                    + FAILURE_SEQUENCE.incrementAndGet();

            Path screenshotPath = screenshotDirectory.resolve(baseName + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath).setFullPage(true));
            System.out.println("[SOAK FAILURE] " + stepName + " screenshot: " + screenshotPath);

            writeFailureLog(logDirectory.resolve(baseName + ".log"), stepName, testContext,
                    currentUrlOf(page), failureMessage, throwable, screenshotPath);

            LAST_CAPTURED.set(screenshotPath.toString());
            return screenshotPath.toString();
        } catch (Exception exception) {
            System.err.println("[SOAK FAILURE] Could not capture failure evidence for " + stepName
                    + ": " + exception.getMessage());
            return null;
        }
    }

    private static void writeFailureLog(Path logPath, String stepName, String testContext,
                                        String currentUrl, String failureMessage, Throwable throwable,
                                        Path screenshotPath) {
        try {
            String pageTabFolder = SoakRunContext.screenshotFolder(stepName);
            int slash = pageTabFolder.indexOf('/');
            String page = slash > 0 ? pageTabFolder.substring(0, slash) : pageTabFolder;
            String tab = slash > 0 ? pageTabFolder.substring(slash + 1) : "";

            int dot = testContext == null ? -1 : testContext.lastIndexOf('.');
            String testClass = dot > 0 ? testContext.substring(0, dot) : testContext;
            String testMethod = dot > 0 ? testContext.substring(dot + 1) : testContext;

            StringBuilder text = new StringBuilder();
            text.append("Test           : ").append(value(testClass)).append(System.lineSeparator())
                    .append("Test Method    : ").append(value(testMethod)).append(System.lineSeparator())
                    .append("Step           : ").append(value(stepName)).append(System.lineSeparator())
                    .append("Page           : ").append(value(page)).append(System.lineSeparator())
                    .append("Tab            : ").append(tab.isBlank() ? "N/A" : tab).append(System.lineSeparator())
                    .append("Timestamp      : ")
                    .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .append(System.lineSeparator())
                    .append("Current URL    : ").append(value(currentUrl)).append(System.lineSeparator())
                    .append("Failure Message: ").append(value(failureMessage)).append(System.lineSeparator())
                    .append("Exception      : ").append(throwable == null ? "N/A" : stackTraceOf(throwable))
                    .append(System.lineSeparator())
                    .append("Screenshot     : ").append(screenshotPath).append(System.lineSeparator());

            Files.writeString(logPath, text.toString(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            System.err.println("[SOAK FAILURE] Could not write failure log " + logPath + ": "
                    + exception.getMessage());
        }
    }

    private static String currentUrlOf(Page page) {
        try {
            return page.url();
        } catch (Exception exception) {
            return null;
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "step";
        }
        String cleaned = text.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("(^_|_$)", "");
        return cleaned.isBlank() ? "step" : cleaned;
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "N/A" : text;
    }
}