package com.vigilx.pages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.SoakUiUtils;

/**
 * Settings -&gt; Application Settings -&gt; Device -&gt; Notification Rules: validates the page, then
 * flips a few randomly chosen toggles OFF/ON (or ON/OFF) and validates the UI state and the
 * background API each flip fires.
 *
 * <p>Reached from the Application Settings "Device" page ({@link ApplicationSettingsPage#openDevice()}).
 * Toggles are discovered dynamically ({@code .toggle-switch} inside table rows) - no row numbers, no
 * generated ids, no fixed notification names. A toggle that is static/disabled or does not change
 * state is skipped and the next random one is tried. The existing background API monitor is not
 * touched; this class only waits for the response its own click triggers, the same way
 * {@link ApplicationSettingsPage} does.
 *
 * <p>Contract: never throws into the caller; failures are logged and returned as {@code false}.
 */
public class NotificationRulesPage extends BasePage {

    private static final int ELEMENT_TIMEOUT_MS = 10000;
    private static final int STATE_TIMEOUT_MS = 3000;
    private static final int API_TIMEOUT_MS = 8000;
    private static final int MAX_ATTEMPTS = 15;
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final String STATE_SCRIPT = "el => {"
            + " const inp = el.matches('input') ? el : el.querySelector('input[type=checkbox],[role=switch]');"
            + " const c = inp || el; let on;"
            + " if (inp && inp.type === 'checkbox') on = inp.checked;"
            + " else if (c.getAttribute('aria-checked') != null) on = c.getAttribute('aria-checked') === 'true';"
            + " else on = /(^|[\\s_-])(on|active|checked|enabled)($|[\\s_-])/i.test(el.className);"
            + " const dis = (inp && inp.disabled) || c.getAttribute('aria-disabled') === 'true'"
            + " || /disabled/i.test(el.className);"
            + " return (on ? '1' : '0') + (dis ? '1' : '0'); }";

    public NotificationRulesPage(Page page) {
        super(page);
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    public boolean runNotificationRulesFlow() {
        if (!open() || !validatePageLoaded()) {
            return false;
        }
        int target = Math.max(1, Integer.parseInt(
                ConfigReader.getOrDefault("notification.rules.toggle.count", "3")));
        Locator toggles = toggles();
        int total = toggles.count();

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            order.add(i);
        }
        Collections.shuffle(order);

        int validated = 0;
        int attempts = 0;
        boolean allPassed = true;
        for (int index : order) {
            if (validated >= target || attempts >= MAX_ATTEMPTS) {
                break;
            }
            attempts++;
            Locator toggle = toggles.nth(index);
            String name = describe(toggle);
            String state = readState(toggle);
            if (state == null || state.charAt(1) == '1') {
                System.out.println("[NOTIFICATION RULES]   Skipping '" + name + "' (disabled/unreadable).");
                continue;
            }
            boolean startOn = state.charAt(0) == '1';
            System.out.println("[NOTIFICATION RULES]   Toggle '" + name + "' initial state: "
                    + (startOn ? "ON" : "OFF"));

            Flip first = flip(toggle, name, !startOn);
            if (first == Flip.NOT_EDITABLE) {
                System.out.println("[NOTIFICATION RULES]   Skipping '" + name + "' (state does not change).");
                continue;
            }
            Flip second = flip(toggle, name, startOn);
            validated++;
            if (first != Flip.OK || second != Flip.OK) {
                allPassed = false;
            }
        }

        System.out.println("[NOTIFICATION RULES] Toggles validated: " + validated + " (of " + total
                + " found, " + attempts + " attempted) allPassed=" + allPassed);
        if (validated == 0) {
            System.err.println("[NOTIFICATION RULES] No editable toggle was found.");
            return false;
        }
        return allPassed;
    }

    // ---------------------------------------------------------------------
    // Navigation + page validation
    // ---------------------------------------------------------------------

    /** Clicks the "Notification Rules" link on the Application Settings Device sub-page. */
    public boolean open() {
        Locator link = page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Notification Rules").setExact(false)).first();
        if (!SoakUiUtils.waitVisible(link, ELEMENT_TIMEOUT_MS)) {
            System.err.println("[NOTIFICATION RULES] 'Notification Rules' link not found.");
            return false;
        }
        try {
            link.click(new Locator.ClickOptions().setTimeout(ELEMENT_TIMEOUT_MS));
            waitAfterPageNavigation();
            return true;
        } catch (Exception exception) {
            System.err.println("[NOTIFICATION RULES] 'Notification Rules' could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
    }

    private boolean validatePageLoaded() {
        boolean title = SoakUiUtils.waitVisible(
                page.getByText("Notification Rules").first(), ELEMENT_TIMEOUT_MS);
        boolean table = SoakUiUtils.waitVisible(page.locator("table").first(), ELEMENT_TIMEOUT_MS);
        boolean headers = SoakUiUtils.isVisibleQuietly(page.locator("table th, [role=columnheader]").first());
        boolean rows = table && page.locator("table tbody tr").count() > 0;
        boolean hasToggles = table && toggles().count() > 0;
        System.out.println("[NOTIFICATION RULES] Page: title=" + title + " table=" + table + " headers="
                + headers + " rows=" + rows + " toggles=" + hasToggles);
        return title && table && headers && rows && hasToggles;
    }

    // ---------------------------------------------------------------------
    // Toggle helpers
    // ---------------------------------------------------------------------

    private enum Flip { OK, NOT_EDITABLE, FAILED }

    private Locator toggles() {
        return page.locator("table tbody tr .toggle-switch");
    }

    /** Clicks the toggle, waits for the state to reach {@code expectedOn}, and checks the API it fired. */
    private Flip flip(Locator toggle, String name, boolean expectedOn) {
        Response response = SoakUiUtils.clickAndWaitForResponse(page, toggle, this::isMutationResponse,
                API_TIMEOUT_MS);
        if (!waitForState(toggle, expectedOn)) {
            return Flip.NOT_EDITABLE;
        }
        boolean apiOk = response != null && response.status() < 400;
        System.out.println("[NOTIFICATION RULES]   '" + name + "' -> " + (expectedOn ? "ON" : "OFF")
                + ": state=OK api=" + (response == null ? "none" : SoakUiUtils.safeMethod(response) + " "
                + SoakUiUtils.shortPath(response.url()) + " " + response.status()));
        return apiOk ? Flip.OK : Flip.FAILED;
    }

    private boolean isMutationResponse(Response response) {
        String type = response.request().resourceType();
        return ("fetch".equals(type) || "xhr".equals(type))
                && !READ_ONLY_METHODS.contains(response.request().method().toUpperCase());
    }

    private boolean waitForState(Locator toggle, boolean expectedOn) {
        long deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS;
        do {
            String state = readState(toggle);
            if (state != null && (state.charAt(0) == '1') == expectedOn) {
                return true;
            }
            page.waitForTimeout(100);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    /** Two chars: [ON?][DISABLED?] as '1'/'0', or {@code null} if unreadable. */
    private String readState(Locator toggle) {
        try {
            return String.valueOf(toggle.evaluate(STATE_SCRIPT));
        } catch (Exception exception) {
            return null;
        }
    }

    /** "Row text / control label" for logs, so nothing depends on a fixed name. */
    private String describe(Locator toggle) {
        try {
            String label = String.valueOf(toggle.evaluate(
                    "el => (el.querySelector('[aria-label]') || el).getAttribute('aria-label') || ''"));
            String row = toggle.locator("xpath=ancestor::tr[1]").innerText().trim()
                    .replaceAll("\\s+", " ");
            return (row.length() > 60 ? row.substring(0, 60) : row) + " | " + label;
        } catch (Exception exception) {
            return "<unnamed toggle>";
        }
    }
}
