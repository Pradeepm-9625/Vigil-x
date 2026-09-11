package com.vigilx.pages;

/**
 * Test-data contract for {@link OrganizationPage#runOrganizationUpdateFlow(OrganizationData)} -
 * caller-supplied, never hard-coded inside {@link OrganizationPage}.
 *
 * @param logoPath path to the image to upload as the organization logo, resolved relative to the
 *                 working directory
 * @param adminLastName the value to set as Admin Details' Last Name
 * @param primaryContactLastName the value to set as Primary Contact's Last Name (after a separate,
 *                 unmodified "save changes" is exercised first)
 * @param addressLine1 the value to set as Address Details' Address Line 1
 * @param addressLine2 the value to set as Address Details' Address Line 2
 */
public record OrganizationData(String logoPath, String adminLastName, String primaryContactLastName,
        String addressLine1, String addressLine2) {
}
