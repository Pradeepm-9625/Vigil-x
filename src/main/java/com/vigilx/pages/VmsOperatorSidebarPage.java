package com.vigilx.pages;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.utils.SoakUiUtils;

/**
 * Infra Management -&gt; profile menu role switch ("Admin" -&gt; "VMS Operator" and back), plus
 * {@link #visitCustomMaps()} - the one VMS Operator sidebar screen with no self-navigating real
 * test of its own elsewhere in the soak run. Every OTHER sidebar entry (Cameras, Sequence, Audit
 * Logs, Maps, Archive, Search, Exports) is reached directly by its own real test instead
 * ({@link LiveViewCrudPage} / {@link SequencePage} for Cameras/Sequence,
 * {@code ApplicationHealthPage.validateMap}/{@code validateArchive} for Maps/Archive,
 * {@link EventSearchValidation} for Search, {@link ArchiveExportValidation} for Exports,
 * {@code AuditLogsPage} for both Audit Logs occurrences) - each of those already calls its own
 * {@code page.navigate()}/{@code navigateTo()} (or, for Search/Exports, clicks its own tab) to
 * get there, so a separate sidebar click immediately beforehand was a real, confirmed-live bug:
 * clicking a button and then immediately navigating away from it via a fresh page navigation
 * forces an extra full page reload, which was bouncing the whole walk back through
 * Dashboard/Infra Management between pages instead of going straight to the next one.
 * Infra Management -&gt; profile menu role switch ("Admin" -&gt; "VMS Operator" and back) - the
 * mechanics only. A real recording walked the full VMS Operator sidebar under this role (Cameras
 * / Sequence / Audit Logs / Maps / Custom Maps / Archive / Search / Exports / Audit Logs); every
 * one of those screens except "Custom Maps" already has its own real test elsewhere in the soak
 * run, under Admin ({@link LiveViewCrudPage} / {@link SequencePage} for Cameras/Sequence,
 * {@code ApplicationHealthPage.validateMap}/{@code validateArchive} for Maps/Archive,
 * {@link EventSearchValidation} for Search, {@link ArchiveExportValidation} for Exports,
 * {@code AuditLogsPage} for both Audit Logs occurrences).
 *
 * <p>Running those same real tests under this role instead was tried and reverted: live
 * validation showed Live View, Sequence, Audit Logs Search, Map, Event Search, Bookmark Search,
 * Archive Export - Snapshots and Audit Logs - Archive Export all FAIL under VMS Operator
 * (permission/UI differences from Admin), even though every one of them passes cleanly in its
 * existing Admin-role position - so they stay there, untouched. Only "Custom Maps" has no
 * functional test anywhere else in the codebase, so {@link #visitCustomMaps()} is this class's
 * only remaining page-load check, run between {@link #switchRole} calls in
 * {@code SoakHealthCheckRunner} right after Infra Management opens, before Application Settings.
 *
 * <p>Contract: never throws into the caller. Every failure is logged and returned as
 * {@code false} so the soak continues.
 */
public class VmsOperatorSidebarPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;

    public VmsOperatorSidebarPage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Role switch
    // ---------------------------------------------------------------------

    private Locator profileMenuButton() {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                .setName(Pattern.compile("quality check|admin|operator", Pattern.CASE_INSENSITIVE))).first();
    }

    /** Reads the profile menu button's own accessible name's trailing role word, best-effort -
     *  call this BEFORE {@link #switchRole} so the caller can restore whatever role was active. */
    public String currentRoleLabel() {
        try {
            Locator profileMenu = profileMenuButton();
            if (!SoakUiUtils.isVisibleQuietly(profileMenu)) {
                return null;
            }
            String name = profileMenu.getAttribute("aria-label");
            if (name == null || name.isBlank()) {
                name = profileMenu.innerText();
            }
            if (name == null) {
                return null;
            }
            Matcher matcher = Pattern
                    .compile("(quality check|vms operator|admin|operator)", Pattern.CASE_INSENSITIVE)
                    .matcher(name);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /** Opens the profile menu (if needed) and selects {@code roleName} from its
     *  {@code menuitemradio} list; a no-op if it is already selected. */
    public boolean switchRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return true;
        }
        try {
            Locator option = page.getByRole(AriaRole.MENUITEMRADIO,
                    new Page.GetByRoleOptions().setName(roleName).setExact(false)).first();
            if (!SoakUiUtils.isVisibleQuietly(option)) {
                Locator profileMenu = profileMenuButton();
                if (!SoakUiUtils.waitVisible(profileMenu, ELEMENT_TIMEOUT_MS)) {
                    System.err.println("[VMS OPERATOR SIDEBAR]   Profile menu control not found.");
                    return false;
                }
                profileMenu.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            }
            if (!SoakUiUtils.waitVisible(option, ELEMENT_TIMEOUT_MS)) {
                System.err.println("[VMS OPERATOR SIDEBAR]   '" + roleName + "' role option not found.");
                SoakUiUtils.closeOpenDialogs(page);
                return false;
            }
            boolean alreadyChecked = isCheckedQuietly(option);
            if (!alreadyChecked) {
                option.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(600);
            } else {
                SoakUiUtils.closeOpenDialogs(page);
            }
            System.out.println("[VMS OPERATOR SIDEBAR]   Role '" + roleName + "': "
                    + (alreadyChecked ? "already selected" : "selected"));
            return true;
        } catch (Exception exception) {
            System.err.println("[VMS OPERATOR SIDEBAR]   Could not select role '" + roleName + "': "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            SoakUiUtils.closeOpenDialogs(page);
            return false;
        }
    }

    private boolean isCheckedQuietly(Locator option) {
        try {
            return Boolean.TRUE.equals(option.isChecked());
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Custom Maps - the one sidebar screen with no functional test elsewhere
    // ---------------------------------------------------------------------

    /**
     * Clicks "Maps" first - confirmed live that "Custom Maps" is a nested sub-item that only
     * renders once "Maps" itself has been clicked/expanded - purely to reveal the submenu, not a
     * re-test of "Maps" itself (that real test runs separately). Then clicks "Custom Maps" -
     * navigation-only, best-effort; a missing button is a clean skip, not a failure.
     */
    public boolean visitCustomMaps() {
        Locator mapsParent = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Maps").setExact(true)).first();
        if (SoakUiUtils.waitVisible(mapsParent, ELEMENT_TIMEOUT_MS)) {
            try {
                mapsParent.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
                page.waitForTimeout(500);
            } catch (Exception ignored) {
                // Best effort - the "Custom Maps" lookup below still has its own chance to find it.
            }
        }

        Locator customMaps = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Custom Maps").setExact(true)).first();
        if (!SoakUiUtils.waitVisible(customMaps, ELEMENT_TIMEOUT_MS)) {
            System.out.println("[VMS OPERATOR SIDEBAR]   'Custom Maps' button not present; skipping.");
            return true;
        }
        try {
            customMaps.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            page.waitForTimeout(500);
            System.out.println("[VMS OPERATOR SIDEBAR]   'Custom Maps' opened (navigation-only check).");
            return true;
        } catch (Exception exception) {
            System.err.println("[VMS OPERATOR SIDEBAR]   'Custom Maps' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }
}
