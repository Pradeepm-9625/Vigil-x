package com.vigilx.pages;

/**
 * The data one Custom Group create/update needs. Built by the caller (from config / test data)
 * and handed to {@link GroupsPage} - the page object only performs the UI actions, it never
 * hard-codes any of these values itself.
 *
 * @param groupName    the group's name (also the unique key {@link GroupsPage} looks it up by)
 * @param email        whether to select the "Email" notification option
 * @param whatsapp     whether to select the "Whatsapp" notification option
 * @param sms          whether to select the "SMS" notification option
 */
public record GroupData(String groupName, boolean email, boolean whatsapp, boolean sms) {
}
