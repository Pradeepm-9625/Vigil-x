package com.vigilx.pages;

/**
 * Test-data contract for {@link QCPage}'s checklist item methods - caller-supplied, never
 * hard-coded inside {@link QCPage}.
 *
 * @param name the checklist item's text
 */
public record QCItemData(String name) {
}
