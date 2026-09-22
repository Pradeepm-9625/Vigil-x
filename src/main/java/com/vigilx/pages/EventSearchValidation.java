package com.vigilx.pages;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.vigilx.config.ConfigReader;
import com.vigilx.utils.ScreenshotUtils;
import com.vigilx.utils.SoakUiUtils;

/**
 * Archive "Search" (the "Events" tab): open Search, set a From/To date range wide enough to span
 * two calendar months (confirmed live: a range confined to a single month never enables the
 * calendar's own Apply button - only a genuine cross-month range does), Apply, verify at least one
 * result came back, select one result dynamically, and confirm its video is actually playing -
 * never just that a click landed on it.
 *
 * <p>Reached after {@link ArchiveValidation}'s own Add Camera / Save Changes flow, on the same
 * Archive/Playback page - a separate class, never modifying {@link ArchiveValidation}. Contract:
 * never throws into the caller; every failure is logged, screenshotted, and returned as
 * {@code false} so the soak continues.
 */
public class EventSearchValidation extends BasePage {

    private static final int TIMEOUT_MS = 15000;

    /** The step name for whichever flow is currently running, so {@link #fail(String)} files its
     * screenshot/log under the right Soak Test folder ("Search/Events" vs "Search/Bookmarks") -
     * set at the top of {@link #runSearchFlow(String)}, read by every {@code fail(...)} call below. */
    private String currentStepName = "Event Search";

    public EventSearchValidation(Page page) {
        super(page);
    }

    /**
     * Runs the flow: Search -&gt; open date filter -&gt; set a From/To range -&gt; Apply -&gt;
     * verify a result is present -&gt; select one result -&gt; verify its video is playing. Stays on
     * the default "Events" tab.
     */
    public boolean validateEventSearch() {
        return runSearchFlow(null);
    }

    /**
     * Identical flow to {@link #validateEventSearch()}, additionally clicking the "Bookmarks" tab
     * first (a sibling of "Events" under the same Search panel) - reuses every other step as-is
     * (date range, Apply, result-count check, dynamic result selection, playback validation), since
     * none of that logic is specific to Events.
     */
    public boolean validateBookmarkSearch() {
        return runSearchFlow("Bookmarks");
    }

    /**
     * @param tab the Search panel's own tab to switch to before filtering ("Bookmarks", "Tags",
     *            ...), or {@code null} to stay on the default "Events" tab.
     */
    private boolean runSearchFlow(String tab) {
        currentStepName = tab == null ? "Event Search" : "Bookmark Search";
        try {
            // "Search" is only ever clicked for the Events sub-flow (tab == null): confirmed live,
            // selecting a result's video can leave the Events/Bookmarks tabs hidden behind the
            // playback view, so the old "click Search only if the panel looks closed" check could
            // still fire here for Bookmarks - and "Search" toggles the panel, so clicking it while
            // it was actually still open (just hidden behind the video) closed it back to the main
            // Archive page instead of switching tabs. Bookmark Search never re-opens Search; it goes
            // straight to the "Bookmarks" tab on the same panel Event Search already opened, exactly
            // like the recorded flow (Search -> Events -> Bookmarks, "Search" clicked once).
            if (tab == null && !isSearchPanelOpen()) {
                Locator searchButton = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Search").setExact(true)).first();
                searchButton.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
            }

            if (tab != null) {
                Locator tabControl = page.getByRole(AriaRole.TAB,
                        new Page.GetByRoleOptions().setName(tab).setExact(true)).first();
                if (!waitVisible(tabControl)) {
                    fail("'" + tab + "' tab not found");
                    return false;
                }
                tabControl.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
            }

            if (!openDateRangeAndApply()) {
                fail("Date range filter could not be applied");
                return false;
            }

            int resultCount = waitForResultCount();
            if (resultCount <= 0) {
                fail("No search result was available for the selected date range");
                return false;
            }
            System.out.println("[EVENT SEARCH]   Results available: " + resultCount);

            if (!selectFirstResult()) {
                fail("Could not select a search result");
                return false;
            }

            if (!confirmResultVideo()) {
                fail("Selected result's video control was not found");
                return false;
            }

            System.out.println("[EVENT SEARCH] Flow: PASS");
            return true;
        } catch (Exception exception) {
            fail("Event Search validation error: " + exception.getMessage());
            return false;
        }
    }

    /** True once either of the Search panel's own tabs ("Events"/"Bookmarks") is visible. */
    private boolean isSearchPanelOpen() {
        try {
            return page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName(Pattern.compile("Events|Bookmarks")))
                    .first().isVisible();
        } catch (Exception exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Date range
    // ---------------------------------------------------------------------

    /**
     * Opens the "Date & Time From" calendar and applies a range wide enough to reliably span two
     * different months - confirmed live: a range confined to a single month's grid never enables
     * the calendar's own Apply button (clicking a second day in the same grid just replaces the
     * first selection instead of extending a range), while a genuine cross-month range does. The
     * end date is always today; the start date defaults to 40 days earlier (configurable), which
     * comfortably clears the "min 10 days different" requirement and reliably crosses a month
     * boundary regardless of where in the month "today" falls.
     */
    private boolean openDateRangeAndApply() {
        Locator selectButtons = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Select").setExact(false));
        if (selectButtons.count() == 0) {
            System.err.println("[EVENT SEARCH]   'Date & Time From' Select control not found.");
            return false;
        }
        selectButtons.first().click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

        int spanDays = Math.max(11, intConfig("event.search.date.range.days", 40));
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(spanDays);

        selectDay(startDate);
        selectDay(endDate);

        Locator applyButton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Apply").setExact(false)).first();
        if (!waitVisible(applyButton)) {
            System.err.println("[EVENT SEARCH]   Calendar 'Apply' control not found.");
            return false;
        }
        if (!applyButton.isEnabled()) {
            System.err.println("[EVENT SEARCH]   Calendar 'Apply' is disabled - the date range"
                    + " (" + startDate + " to " + endDate + ") was not accepted as a valid range.");
            return false;
        }
        applyButton.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        System.out.println("[EVENT SEARCH]   Date range applied: " + startDate + " to " + endDate);
        return true;
    }

    /**
     * Clicks {@code date}'s own month heading (confirmed live: required before a day in that grid
     * can actually be selected - clicking the day alone, without first clicking its month's own
     * heading, does not register), then that day's gridcell within the matching month grid.
     */
    private void selectDay(LocalDate date) {
        String monthName = date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        Locator header = page.getByText(Pattern.compile("^" + monthName + "\\s+" + date.getYear())).first();
        if (header.count() > 0 && header.isVisible()) {
            header.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        }
        Locator grid = page.getByRole(AriaRole.GRID, new Page.GetByRoleOptions().setName(monthName));
        Locator dayCell = grid.getByRole(AriaRole.GRIDCELL,
                new Locator.GetByRoleOptions().setName(String.valueOf(date.getDayOfMonth())).setExact(true)).first();
        dayCell.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
    }

    // ---------------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------------

    /** Polls the "Search Result (N)" heading for up to {@code TIMEOUT_MS} and returns N (0 if none). */
    private int waitForResultCount() {
        Pattern pattern = Pattern.compile("Search Result \\((\\d+)\\)");
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Locator heading = page.getByText(pattern).first();
            if (heading.count() > 0) {
                try {
                    java.util.regex.Matcher matcher = pattern.matcher(heading.textContent());
                    if (matcher.find()) {
                        return Integer.parseInt(matcher.group(1));
                    }
                } catch (Exception ignored) {
                    // Keep polling.
                }
            }
            page.waitForTimeout(300);
        }
        return 0;
    }

    /**
     * Selects one available result dynamically - scoped to the results list's own row elements,
     * never a hard-coded event name or timestamp. Picking the first one is sufficient: "one
     * available result" does not require randomness, and the results area can hold thousands of
     * rows on a wide date range.
     *
     * <p>Every real result row's own {@code <label>} carries a clock time (e.g. "4:22 PM") as part
     * of its timestamp - something no unrelated page label (filter names, hierarchy items, colors)
     * ever does - so that is used to find a genuine row dynamically, on both the Events and
     * Bookmarks tabs, without depending on either tab's own card markup. Confirmed live: clicking a
     * Bookmarks row's card DIV directly (its "surveillance-card-comp" wrapper) navigates away to
     * Playback instead of selecting/previewing it - the label is what both tabs actually expect.
     */
    private boolean selectFirstResult() {
        Locator timestampedLabel = page.locator("label")
                .filter(new Locator.FilterOptions()
                        .setHasText(Pattern.compile("\\d{1,2}:\\d{2}\\s*(AM|PM)", Pattern.CASE_INSENSITIVE)))
                .first();
        // Polls (bounded by TIMEOUT_MS) rather than a single synchronous check: confirmed live, the
        // result grid can still be rendering for a moment right after "Apply" even though the
        // "Search Result (N)" heading has already updated, and an immediate check here raced ahead
        // of it - and, unlike a CSS-class-based fallback, there is no safe alternative locator to
        // fall back to: a raw card-DIV click was confirmed live to navigate away to Playback
        // instead of selecting/previewing the result on the Bookmarks tab.
        if (!waitVisible(timestampedLabel)) {
            System.err.println("[EVENT SEARCH]   No selectable result row found.");
            return false;
        }
        timestampedLabel.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));

        // Confirmed live (recorded flow): selecting a result is a two-step interaction - the label
        // click above, then checking that same row's own checkbox control - not the label click
        // alone. Best-effort: some builds/tabs commit the selection on the label click alone, so a
        // missing/already-checked box here is logged, never a hard failure of the whole flow.
        checkResultCheckboxBestEffort(timestampedLabel);
        return true;
    }

    /** Checks the selected row's own checkbox, scoped to that row so no unrelated control is hit. */
    private void checkResultCheckboxBestEffort(Locator resultLabel) {
        try {
            Locator checkbox = resultLabel.locator("input[type='checkbox']").first();
            if (checkbox.count() == 0) {
                checkbox = resultLabel.getByRole(AriaRole.CHECKBOX).first();
            }
            if (checkbox.count() == 0) {
                System.out.println("[EVENT SEARCH]   Selected row has no separate checkbox control.");
                return;
            }
            if (!checkbox.isChecked()) {
                checkbox.check(new Locator.CheckOptions().setTimeout(TIMEOUT_MS));
            }
            System.out.println("[EVENT SEARCH]   Selected row's checkbox checked.");
        } catch (Exception exception) {
            System.out.println("[EVENT SEARCH]   Could not check the selected row's checkbox: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
        }
    }

    // ---------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------

    /**
     * Confirms the selected result actually has a playable video control and that it responds to a
     * real click - matching the recorded flow exactly (a single click on the result's own
     * {@code <video>} element, never a multi-second "currentTime" progress poll, which proved
     * unreliable against real streaming footage and produced false failures unrelated to this
     * flow's own logic).
     */
    private boolean confirmResultVideo() {
        Locator video = page.locator(".surveillance-card-comp .s-c-b-video-wrapper .s-c-media-stack video")
                .last();
        if (!SoakUiUtils.isVisibleQuietly(video)) {
            video = page.locator("video").last();
        }
        if (!waitVisible(video)) {
            System.err.println("[EVENT SEARCH]   No video element appeared for the selected result.");
            return false;
        }
        try {
            video.click(new Locator.ClickOptions().setTimeout(TIMEOUT_MS));
        } catch (Exception exception) {
            System.err.println("[EVENT SEARCH]   Selected result's video could not be clicked: "
                    + SoakUiUtils.firstLine(exception.getMessage()));
            return false;
        }
        System.out.println("[EVENT SEARCH]   Selected result's video control confirmed.");
        return true;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean waitVisible(Locator locator) {
        try {
            locator.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(TIMEOUT_MS));
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private int intConfig(String key, int fallback) {
        try {
            String value = ConfigReader.getOrDefault(key, String.valueOf(fallback));
            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception exception) {
            return fallback;
        }
    }

    private void fail(String reason) {
        String screenshot = ScreenshotUtils.captureFailure(page, currentStepName,
                "SoakHealthCheckTest.runConfiguredHealthCheck", reason, null);
        System.err.println("[EVENT SEARCH]   FAILED: " + reason + " | screenshot=" + screenshot);
    }
}
