package com.example.test_android_dev;

/**
 * App configuration and feature flags.
 */
public class Config {

    /**
     * Master switch for mock mode.
     * If true, the app will not make real network calls but will use fake data generators.
     * This is useful for testing UI and logic flows without a backend.
     * Set to false to connect to a real backend server.
     */
    public static final boolean MOCK_MODE = false; // Set to false for real backend testing

    /**
     * Debug mode switch for UI.
     * If true, shows the developer test interface (manual text input).
     * If false, shows the user voice interface (press-to-talk button).
     * Set to false for production builds.
     */
    public static final boolean DEBUG_MODE = false; // Set to true for development testing
}
