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
}