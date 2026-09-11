package com.vigilx.pages;

/**
 * Test-data contract for {@link ProjectInformationPage#runProjectInformationFlow(ProjectInformationData)}
 * - caller-supplied, never hard-coded inside {@link ProjectInformationPage}.
 *
 * @param logoPath path to the image to upload as the project logo, resolved relative to the
 *                 working directory
 * @param description an interim value to set as Project Basic Information's Description,
 *                 verified then immediately re-edited to {@code finalDescription}
 * @param finalDescription the value Description is updated to after {@code description} - this is
 *                 the one actually verified as displayed at the end of the flow
 * @param adminLastName the value to set as Project Admin Details' Last Name
 * @param addressLine1 the value to set as Address Details' Address Line 1 (Address Line 2 and
 *                 every other address field are left untouched)
 */
public record ProjectInformationData(String logoPath, String description, String finalDescription,
        String adminLastName, String addressLine1) {
}
