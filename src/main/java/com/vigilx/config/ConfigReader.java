package com.vigilx.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * Reads application configuration from config.properties.
 * Loads the configuration only once during framework startup.
 */
public final class ConfigReader {

    private static final String CONFIG_FILE = "config.properties";
    private static final Properties properties = new Properties();

    static {
        loadProperties();
    }

    private ConfigReader() {
        // Prevent instantiation
    }

    /**
     * Loads configuration properties.
     */
    private static void loadProperties() {

        try (InputStream inputStream = ConfigReader.class
                .getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {

            Objects.requireNonNull(inputStream,
                    CONFIG_FILE + " file not found inside resources folder.");

            properties.load(inputStream);

        } catch (IOException exception) {

            throw new RuntimeException(
                    "Unable to load configuration file : " + CONFIG_FILE,
                    exception);

        }

    }

    /**
     * Returns property value.
     *
     * @param key Property key
     * @return Property value
     */
    public static String get(String key) {
        String value = System.getProperty(key, properties.getProperty(key));

        if (value == null || value.trim().isEmpty()) {

            throw new RuntimeException(
                    "Configuration key not found : " + key);

        }

        return value.trim();

    }

    /** Returns a configured value or the supplied default; blank values are allowed. */
    public static String getOrDefault(String key, String defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        return value == null ? defaultValue : value.trim();
    }

    /**
     * Returns Integer property.
     */
    public static int getInt(String key) {

        return Integer.parseInt(get(key));

    }

    /**
     * Returns Boolean property.
     */
    public static boolean getBoolean(String key) {

        return Boolean.parseBoolean(get(key));

    }

}
