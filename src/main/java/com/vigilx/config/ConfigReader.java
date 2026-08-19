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

    /**
     * Optional developer/CI overrides that are never committed. Values here win over
     * {@link #CONFIG_FILE}; a {@code -D} system property still wins over both.
     */
    private static final String LOCAL_CONFIG_FILE = "config.local.properties";

    private static final Properties properties = new Properties();

    static {
        loadProperties();
        loadLocalOverrides();
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
     * Overlays {@link #LOCAL_CONFIG_FILE} when it is present on the classpath.
     *
     * <p>This is how real credentials stay out of version control: {@code config.properties} holds
     * placeholders and is committed, while {@code config.local.properties} holds the working values
     * and is git-ignored. Absence of the file is normal and never an error.
     */
    private static void loadLocalOverrides() {

        try (InputStream inputStream = ConfigReader.class
                .getClassLoader()
                .getResourceAsStream(LOCAL_CONFIG_FILE)) {

            if (inputStream == null) {
                return;
            }

            Properties overrides = new Properties();
            overrides.load(inputStream);
            properties.putAll(overrides);

            System.out.println("[CONFIG] Applied " + overrides.size()
                    + " local override(s) from " + LOCAL_CONFIG_FILE);

        } catch (IOException exception) {
            // Overrides are optional: fall back to the committed defaults rather than failing.
            System.err.println("[CONFIG] Could not read " + LOCAL_CONFIG_FILE + ": "
                    + exception.getMessage());
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

        value = value.trim();
        if ("base.url".equals(key)) {
            value = value.replaceFirst("/+$", "");
        }
        return value;

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
