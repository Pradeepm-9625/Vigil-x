package com.vigilx.apisecurity.inventory;

/**
 * Business/purpose category for one API - used for load-testing profile selection (each category
 * gets its own JMeter profile) and now also carried on {@link FinalApiRecord} for general reporting.
 */
public enum ApiCategory {
    AUTH, STREAMING, CONTROL, DEVICE, REPORTING, READ, CREATE, UPDATE, DELETE, OTHER
}
