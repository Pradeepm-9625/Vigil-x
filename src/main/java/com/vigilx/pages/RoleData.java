package com.vigilx.pages;

/**
 * The data one Custom Role create/update needs. Built by the caller (from config / test data) and
 * handed to {@link RolesPage} - the page object only performs the UI actions, it never hard-codes
 * any of these values itself.
 *
 * @param roleName       the role's name (also the unique key {@link RolesPage} looks it up by)
 * @param permissionFull whether to select the "Full" permission level while creating the role
 */
public record RoleData(String roleName, boolean permissionFull) {
}
