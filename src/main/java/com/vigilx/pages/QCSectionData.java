package com.vigilx.pages;

/**
 * Test-data contract for {@link QCPage}'s checklist section methods - caller-supplied, never
 * hard-coded inside {@link QCPage}.
 *
 * @param name the section title
 * @param description the section description
 */
public record QCSectionData(String name, String description) {
}
