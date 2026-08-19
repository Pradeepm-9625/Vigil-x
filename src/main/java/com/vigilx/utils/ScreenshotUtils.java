package com.vigilx.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.microsoft.playwright.Page;

public final class ScreenshotUtils {

    private static final String SCREENSHOT_FOLDER = "screenshots";

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

            return screenshotPath.toString();

        } catch (Exception exception) {
            System.err.println("[SCREENSHOT FAILURE] Could not save " + name + ": " + exception.getMessage());
            return null;
        }
    }
}