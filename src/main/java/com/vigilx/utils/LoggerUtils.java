package com.vigilx.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for creating Log4j loggers.
 */
public final class LoggerUtils {

    private LoggerUtils() {
        // Prevent instantiation
    }

    /**
     * Returns a logger for the given class.
     *
     * @param clazz Class requesting the logger
     * @return Logger instance
     */
    public static Logger getLogger(Class<?> clazz) {
        return LogManager.getLogger(clazz);
    }
}