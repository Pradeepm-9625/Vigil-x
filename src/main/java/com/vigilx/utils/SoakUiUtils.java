package com.vigilx.utils;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Small, stateless UI helpers shared across the soak-safe page validations - currently
 * {@link com.vigilx.pages.ProjectHierarchyValidation}: closing a stray modal, reading a toast, and
 * clicking a control while blocking on its API response (pass or fail).
 *
 * <p>Kept as static utilities that take {@link Page} explicitly, rather than added to
 * {@link com.vigilx.pages.BasePage}, so adopting them can never collide with a same-named private
 * helper a validation class already has of its own (several do, e.g.
 * {@code DeviceDetailsValidation}, {@code ArchiveValidation}, {@code MapValidation} each keep their
 * own {@code slug()}) - a protected method here would force those into an illegal "weaker access"
 * override the moment they extend {@link com.vigilx.pages.BasePage}, so this stays a separate,
 * explicitly-called utility class instead. New validation classes are free to call these directly
 * instead of copying the same logic again.
 */
public final class SoakUiUtils {

    private static final String DIALOG_MARKERS = "dialog[open], .vxmodal__overlay, .vxpanelmodal-overlay";

    private SoakUiUtils() {
    }

    public static boolean isVisibleQuietly(Locator locator) {
        try {
            return locator.isVisible();
        } catch (Exception exception) {
            return false;
        }
    }

    /** Waits up to {@code timeoutMs} for {@code locator} to become visible; never throws. */
    public static boolean waitVisible(Locator locator, int timeoutMs) {
        try {
            locator.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    /** First visible toast / snackbar / alert text on the page, normalised to one line. */
    public static String readToastText(Page page) {
        try {
            Locator toasts = page.locator(
                    "#common-toast-2, [id^='common-toast'], [class*='toast' i], [role='alert'], [class*='snackbar' i]");
            int count = Math.min(toasts.count(), 8);
            for (int index = 0; index < count; index++) {
                Locator toast = toasts.nth(index);
                if (isVisibleQuietly(toast)) {
                    String text = toast.innerText();
                    if (text != null && !text.isBlank()) {
                        return text.strip().replaceAll("\\s+", " ");
                    }
                }
            }
        } catch (Exception ignored) {
            // No readable toast; caller falls back to its own close/timeout signals.
        }
        return "";
    }

    public static boolean isAnyDialogOpen(Page page) {
        try {
            Locator dialogs = page.locator(DIALOG_MARKERS);
            int count = Math.min(dialogs.count(), 6);
            for (int index = 0; index < count; index++) {
                if (isVisibleQuietly(dialogs.nth(index))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Treat a read error as "nothing to close".
        }
        return false;
    }

    /**
     * Closes any open modal dialog: Escape first (native {@code <dialog aria-modal>} responds to
     * it), then the app's explicit close controls, then - as a last resort - a forced
     * {@code dialog.close()} on every {@code dialog[open]}. Re-checks after each step and returns
     * as soon as nothing is open.
     */
    public static void closeOpenDialogs(Page page) {
        String[] closeSelectors = {
                "[aria-label='Close dialog' i]",
                "[class*='modal'] [aria-label*='close' i], [class*='modal__close' i], .vxmodal__close",
                ".vxmodal__overlay-hitbox"
        };
        for (int attempt = 0; attempt < 4; attempt++) {
            if (!isAnyDialogOpen(page)) {
                return;
            }
            try {
                page.keyboard().press("Escape");
            } catch (Exception ignored) {
                // Keep going.
            }
            page.waitForTimeout(400);
            if (!isAnyDialogOpen(page)) {
                return;
            }
            for (String selector : closeSelectors) {
                try {
                    Locator close = page.locator(selector).first();
                    if (close.count() > 0 && close.isVisible()) {
                        close.click(new Locator.ClickOptions().setTimeout(2500).setForce(true));
                        page.waitForTimeout(400);
                        if (!isAnyDialogOpen(page)) {
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // Try the next close control.
                }
            }
            try {
                page.evaluate("() => document.querySelectorAll('dialog[open]')"
                        + ".forEach(d => { try { d.close(); } catch (e) {} })");
            } catch (Exception ignored) {
                // Best effort.
            }
            page.waitForTimeout(400);
        }
    }

    /** A named Cancel/Discard/Close button first, {@link #closeOpenDialogs} as the guarantee. */
    public static void dismissDialog(Page page) {
        try {
            Locator close = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions()
                    .setName(Pattern.compile("^\\s*(cancel|discard|close)\\s*$", Pattern.CASE_INSENSITIVE))).first();
            if (close.count() > 0 && close.isVisible()) {
                close.click(new Locator.ClickOptions().setTimeout(3000));
                page.waitForTimeout(400);
            }
        } catch (Exception ignored) {
            // Fall through to the hard close below.
        }
        closeOpenDialogs(page);
    }

    /**
     * Clicks {@code control} and blocks until a response matching {@code matcher} arrives - pass or
     * fail - instead of guessing the outcome from a toast. Never throws: returns {@code null} when
     * the click ran but no matching response showed up within {@code timeoutMs}.
     */
    public static Response clickAndWaitForResponse(Page page, Locator control, Predicate<Response> matcher,
                                                     int timeoutMs) {
        try {
            return page.waitForResponse(matcher,
                    new Page.WaitForResponseOptions().setTimeout(timeoutMs),
                    () -> control.click(new Locator.ClickOptions().setTimeout(8000)));
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Clicks {@code control} and waits for a real file download to start - pass or fail - instead
     * of guessing from a toast. Never throws: returns {@code null} when the click ran but no
     * download started within {@code timeoutMs}.
     */
    public static Download clickAndWaitForDownload(Page page, Locator control, int timeoutMs) {
        try {
            return page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(timeoutMs),
                    () -> control.click(new Locator.ClickOptions().setTimeout(8000)));
        } catch (Exception exception) {
            return null;
        }
    }

    /** One search-and-check cycle's outcome: the matched API response (if any) and the rendered result. */
    public record SearchOutcome(Response apiResponse, boolean noDataShown) { }

    /**
     * Fills {@code searchBox} with {@code term}, waits (best-effort) up to {@code apiTimeoutMs} for
     * a response matching {@code apiMatcher}, reads whether the app's {@code no-data-image} empty
     * state is now shown, then clicks {@code clearButton} (if given and visible) to leave the page
     * as it was found. Either a "no data" or a "data available" outcome is valid for an arbitrary
     * search term - it is up to the caller to decide, from the returned {@link SearchOutcome}, what
     * (if anything) counts as a failure. Never throws.
     */
    public static SearchOutcome searchAndReport(Page page, Locator searchBox, String term,
            Predicate<Response> apiMatcher, int apiTimeoutMs, Locator clearButton) {
        Response response;
        try {
            response = page.waitForResponse(apiMatcher, new Page.WaitForResponseOptions().setTimeout(apiTimeoutMs),
                    () -> searchBox.fill(term));
        } catch (Exception exception) {
            response = null;
        }
        boolean noDataShown = waitVisible(page.getByTestId("no-data-image"), 5000);
        if (clearButton != null && waitVisible(clearButton, 3000)) {
            try {
                clearButton.click(new Locator.ClickOptions().setTimeout(5000));
                page.waitForTimeout(400);
            } catch (Exception ignored) {
                // Best effort - leaving the search text in place is not itself a failure.
            }
        }
        return new SearchOutcome(response, noDataShown);
    }

    /**
     * Expands collapsed tree nodes (the {@code ph-v1-dynamic-tree-node} / MUI tree component used
     * by both the Project Hierarchy tree and the Archive "Add Camera" device tree) until no more
     * collapsed branches are found, {@code maxAttempts} passes are used, or {@code budgetMs} of
     * wall-clock time has elapsed (whichever comes first).
     *
     * <p>Matches only {@code [aria-expanded='false']} - the semantic, self-correcting signal a real
     * toggle carries (it flips to {@code true} once expanded, so a clicked node is never re-matched
     * next pass). An earlier version also matched generic icon-container classes that exist on every
     * row whether expandable or not; clicking dozens of those non-toggles each still paid their full
     * click timeout before failing, which on a large tree (100+ rows) turned this into a multi-minute
     * stall. Each click now gets a short timeout for the same reason: a real toggle responds
     * near-instantly, so there is nothing to wait 3s for.
     */
    public static void expandCollapsedTreeNodes(Page page, int maxAttempts, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        for (int attempt = 0; attempt < maxAttempts && System.currentTimeMillis() < deadline; attempt++) {
            Locator expanders = page.locator("[aria-expanded='false']");
            int count = Math.min(expanders.count(), 80);
            if (count == 0) {
                return;
            }
            boolean expanded = false;
            for (int index = 0; index < count && System.currentTimeMillis() < deadline; index++) {
                try {
                    Locator expander = expanders.nth(index);
                    if (expander.count() > 0 && expander.isVisible()) {
                        expander.click(new Locator.ClickOptions().setTimeout(500));
                        expanded = true;
                        page.waitForTimeout(150);
                    }
                } catch (Exception ignored) {
                    // Try the next expander.
                }
            }
            if (!expanded) {
                return;
            }
        }
    }

    /** HTTP method of {@code response}'s request, or "?" if it could not be read. */
    public static String safeMethod(Response response) {
        try {
            return response.request().method();
        } catch (Exception exception) {
            return "?";
        }
    }

    /** {@code url}'s path only (no host/query) for compact logging; {@code url} itself if parsing fails. */
    public static String shortPath(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            return (path == null || path.isBlank()) ? url : path;
        } catch (Exception exception) {
            return url;
        }
    }

    /** {@code response}'s request body, or "" if it could not be read (e.g. no body, or a GET). */
    public static String safeRequestBody(Response response) {
        try {
            String data = response.request().postData();
            return data == null ? "" : data;
        } catch (Exception exception) {
            return "";
        }
    }

    /** {@code response}'s response body text, or "" if it could not be read. */
    public static String safeResponseBody(Response response) {
        try {
            String text = response.text();
            return text == null ? "" : text;
        } catch (Exception exception) {
            return "";
        }
    }

    /** First line of a (possibly multi-line Playwright) error message, for a one-line log. */
    public static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "<no message>";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.strip();
    }

    /** Lower-cased, hyphenated, filename-safe form of {@code value}; used for screenshot names. */
    public static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "item";
        }
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug.isBlank() ? "item" : slug;
    }
}
