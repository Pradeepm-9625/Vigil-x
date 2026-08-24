package com.vigilx.apisecurity.inventory;

/** Where a final-inventory record's identity came from, relative to both discovery sources. */
public enum FinalApiSource {
    /** ApiMonitor captured it; the HAR trace never saw it (or wasn't checked). */
    APIMONITOR,
    /** ApiMonitor never captured it; only the HAR trace (and now MissingApiRegistry) has it. */
    HAR_MISSING,
    /** Both ApiMonitor and the HAR trace captured it - a confirmed duplicate, executed only once. */
    BOTH
}
