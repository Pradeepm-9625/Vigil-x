package com.vigilx.pages;

/**
 * The data one "Add User" run needs. Built by the caller (from config / test data) and handed to
 * {@link UsersRolesPage#createUser(UserData)} - the page object only performs the UI actions, it
 * never hard-codes any of these values itself.
 *
 * @param firstName          user's first name
 * @param lastName           user's last name
 * @param email              user's email (also the unique key the created-user check looks for)
 * @param mobileNumber       user's mobile number
 * @param role               role to select (e.g. "Quality Check")
 * @param group              group to select (e.g. "Test")
 * @param profilePicturePath path to the image to upload, resolved relative to the working
 *                           directory; blank or a missing file makes the picture step skip cleanly
 */
public record UserData(
        String firstName, String lastName, String email, String mobileNumber,
        String role, String group, String profilePicturePath) {
}
