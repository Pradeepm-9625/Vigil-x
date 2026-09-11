package com.vigilx.pages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/** Alerts V1 navigation plus video-playback validation for a randomly selected Video Alert. */
public class alert extends BasePage {

    /**
     * The app renders a still preview image + play overlay first and only mounts the actual
     * {@code <video>} once that overlay is clicked; an alert genuinely having no clip attached
     * is a different, legitimate state from that. This regex recognizes the app explicitly
     * saying so, so that case is never conflated with a real validation failure.
     */
    private static final Pattern NO_CLIP_TEXT =
            Pattern.compile("no\\s*(video|clip|recording|media)\\s*(available|found)?", Pattern.CASE_INSENSITIVE);

    public alert(Page page) {
        super(page);
    }

    public boolean validateAlerts(String baseUrl, Path screenshotPath) {

    String testName = "Video Alert Validation";

    try {
        System.out.println("=================================================");
        System.out.println("STARTING: " + testName);
        System.out.println("=================================================");

        // A. Open Alerts V1.
        navigateTo(baseUrl + "/alerts/v1?sortField=occurredAt&sortDirection=desc");
        System.out.println("[INFO] Navigated to Alerts page.");

        page.getByRole(
                AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Alerts Alerts").setExact(true)
        ).click();
        System.out.println("[INFO] Alerts module opened.");

        page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("Video Alert", Pattern.CASE_INSENSITIVE))
        ).first().click();
        System.out.println("[INFO] Video Alert filter selected.");

        // B. Wait 5s after the Alerts page is loaded so alert cards stabilize.
        System.out.println("[INFO] Waiting 5s for alert cards to stabilize...");
        page.waitForTimeout(5000);

        // =========================================================
        // C. SELECT A RANDOM VIDEO ALERT (not the first one)
        // =========================================================

        Locator videoAlertBadges = page.getByText("Video Alert", new Page.GetByTextOptions().setExact(true));
        int badgeCount = videoAlertBadges.count();

        // The first couple of matches are the page's own "Video Alert" filter button/KPI label,
        // not a table row; real rows start at index 2 (confirmed against the live table).
        int rowOffset = 2;
        if (badgeCount <= rowOffset) {
            System.err.println("[FAIL] No Video Alert rows found to validate.");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        int rowIndex = rowOffset + new Random().nextInt(badgeCount - rowOffset);
        System.out.println("[INFO] Selected random Video Alert " + rowIndex + " of " + badgeCount);

        // D. Open the alert detail/clip.
        videoAlertBadges.nth(rowIndex).click();
        System.out.println("[INFO] Video Alert details opened.");

        Locator overlay = page.locator(".alerts-v1-detail-overlay");
        overlay.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(10000));

        // E. Wait 5s after the alert detail opens.
        System.out.println("[INFO] Waiting 5s for the alert detail to stabilize...");
        page.waitForTimeout(5000);

        return validateOpenAlertDetail(overlay, screenshotPath);

    } catch (Exception exception) {

        System.err.println("=================================================");
        System.err.println("[VIDEO VALIDATION ERROR]");
        System.err.println(exception.getMessage());
        System.err.println("=================================================");

        exception.printStackTrace();

        captureVideoFailureScreenshot(screenshotPath);

        return false;

    } finally {
        closeDetailOverlay();
    }
}

    /**
     * F. through K. - assumes an alert detail overlay is already open and settled (the 5s wait
     * in step E, or equivalent, already happened). Detects whether a playable clip exists using
     * the app's real lifecycle - a still preview + {@code .play-overlay}/{@code img[alt="play"]}
     * render first, and {@code <video>} only mounts once that overlay is clicked; checking for
     * {@code <video>} before that click was the original bug here - then verifies actual
     * playback: play -&gt; progresses -&gt; pause -&gt; confirmed paused -&gt; resume -&gt;
     * progresses again.
     *
     * <p>Public and reusable on purpose: {@link #validateAlerts} calls this after picking one
     * random Video Alert per the required flow, but anything that opens a <em>specific</em>
     * known alert directly (e.g. a targeted search across many alerts for one confirmed to carry
     * a clip) exercises this exact same detection and playback-verification logic instead of a
     * separate, duplicate implementation.
     */
    public boolean validateOpenAlertDetail(Locator overlay, Path screenshotPath) {
      try {
        // =========================================================
        // F. DETECT WHETHER A PLAYABLE ALERT CLIP EXISTS
        // =========================================================

        Locator playerFrame = overlay.locator(".camera-video-player__aspect-frame");
        Locator previewImage = overlay.locator("img[alt='Alert preview']");
        Locator playOverlay = overlay.locator(".play-overlay");
        Locator playIcon = overlay.locator("img[alt='play']");
        Locator directVideo = overlay.locator("video");

        // The overlay's own controls (play-overlay / play icon) can still be mid-mount right
        // after the fixed 5s settle wait above, especially once the browser is already busy from
        // earlier soak steps - confirmed live, where the exact same alert showed the overlay on a
        // fresh browser but not yet under soak load. Poll for up to another 10s instead of
        // deciding "no clip" off one snapshot; the very first satisfied check exits immediately,
        // so a normally-fast mount pays no extra cost.
        boolean hasContainer = false;
        boolean hasPreview = false;
        boolean hasPlayOverlay = false;
        boolean hasPlayIcon = false;
        boolean hasDirectVideo = false;
        long detectDeadline = System.currentTimeMillis() + 10000;
        while (true) {
            hasContainer = playerFrame.count() > 0;
            hasPreview = previewImage.count() > 0;
            hasPlayOverlay = playOverlay.count() > 0;
            hasPlayIcon = playIcon.count() > 0;
            hasDirectVideo = directVideo.count() > 0 && directVideo.first().isVisible();

            boolean ready = hasDirectVideo || (hasPreview && (hasPlayOverlay || hasPlayIcon));
            if (ready || System.currentTimeMillis() >= detectDeadline) {
                break;
            }
            page.waitForTimeout(1000);
        }

        System.out.println("[DETECT] container=" + hasContainer + " previewImage=" + hasPreview
                + " playOverlay=" + hasPlayOverlay + " playIcon=" + hasPlayIcon + " directVideo=" + hasDirectVideo);

        Locator videoElement;

        // L. The application may render <video> directly with no preview/play overlay at all.
        if (hasDirectVideo) {
            System.out.println("[STATE] VIDEO_AVAILABLE (rendered directly, no play overlay needed)");
            videoElement = directVideo.first();

        } else if (hasPreview && (hasPlayOverlay || hasPlayIcon)) {
            // Preview image + play control present: the clip exists but has not been activated
            // yet. This is NOT "video unavailable" - it is pending a play click.
            System.out.println("[STATE] VIDEO_AVAILABLE_PENDING_PLAY");

            // G. Click the play overlay using a robust locator.
            if (!clickPlayOverlay(playOverlay, playIcon)) {
                System.err.println("[FAIL] Preview and play control were present but not clickable.");
                captureVideoFailureScreenshot(screenshotPath);
                return false;
            }
            System.out.println("[INFO] Play overlay clicked.");

            // H. Wait for the actual media element/player state to appear.
            videoElement = waitForVideoAfterPlay(overlay);
            if (videoElement == null) {
                System.err.println("[FAIL] Play overlay was clicked but no video element appeared.");
                captureVideoFailureScreenshot(screenshotPath);
                return false;
            }
            System.out.println("[INFO] Video element attached after play.");

        } else {
            // Neither a direct <video> nor a preview+play pairing appeared even after the 5s
            // settle wait. Distinguish "app explicitly has no clip for this alert" (PASS/EXPECTED)
            // from a genuine validation failure - never assume the former without an explicit
            // signal, since that would hide a real problem.
            if (overlay.getByText(NO_CLIP_TEXT).count() > 0) {
                System.out.println("[PASS/EXPECTED] Application explicitly indicates this alert has no video/clip.");
                closeDetailOverlay();
                return true;
            }

            // N. No iframe, shadow DOM, or portal-mounted player was ever found hosting this
            // control in practice either - logged here (media-stack modifier class) so a real
            // "clip never attached" alert is distinguishable at a glance from a genuine app bug
            // without re-deriving it by hand from a screenshot.
            String mediaStackClass = "";
            try {
                mediaStackClass = (String) overlay.locator(".video-cam-card-media-stack").first()
                        .evaluate("el => el.className");
            } catch (Exception ignored) {
            }
            System.err.println("[FAIL] VIDEO NOT AVAILABLE - no preview image, no play overlay, "
                    + "and no video element found for this Video Alert. mediaStackClass='" + mediaStackClass + "'");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        // =========================================================
        // I. CHECK VIDEO SOURCE
        // =========================================================

        String videoSource = (String) videoElement.evaluate(
                "element => element.currentSrc || element.src || ''"
        );
        System.out.println("[VIDEO SOURCE] " + videoSource);

        if (videoSource == null || videoSource.isBlank()) {
            System.err.println("[FAIL] Video element exists but has no source.");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        String videoError = (String) videoElement.evaluate(
                """
                element => {
                    if (element.error) {
                        return JSON.stringify({ code: element.error.code, message: element.error.message });
                    }
                    return '';
                }
                """
        );
        if (videoError != null && !videoError.isBlank()) {
            System.err.println("[FAIL] Video has playback error: " + videoError);
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        videoElement.evaluate("element => { element.muted = true; element.volume = 0; }");

        try {
            page.waitForFunction(
                    """
                    () => {
                        const video = document.querySelector('.alerts-v1-detail-overlay video');
                        return video && video.readyState >= 2;
                    }
                    """,
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(15000)
            );
        } catch (Exception e) {
            System.err.println("[FAIL] Video source exists but video is not ready.");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        // =========================================================
        // J. VERIFY ACTUAL PLAYBACK: play -> advances -> pause -> resume -> advances again
        // =========================================================

        if (!ensurePlaying(videoElement)) {
            System.err.println("[FAIL] Video could not be started.");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        double firstStart = currentTime(videoElement);
        page.waitForTimeout(3000);
        double firstEnd = currentTime(videoElement);
        boolean firstEnded = ended(videoElement);

        System.out.println("[PLAYBACK 1] " + firstStart + " -> " + firstEnd + " (ended=" + firstEnded + ")");

        if (!progressed(firstStart, firstEnd) || firstEnded) {
            System.err.println("[FAIL] Video did not progress on first play (paused="
                    + paused(videoElement) + ", ended=" + firstEnded + ").");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        // Pause and confirm it actually paused.
        if (!clickClipControl("Pause clip") || !waitForPaused(videoElement, true)) {
            System.err.println("[FAIL] Pause action did not take effect.");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }
        System.out.println("[INFO] Pause confirmed.");

        // Resume and confirm playback advances again.
        if (!clickClipControl("Play clip") || !waitForPaused(videoElement, false)) {
            System.err.println("[FAIL] Resume (Play clip) action did not take effect.");
            captureVideoFailureScreenshot(screenshotPath);
            return false;
        }

        double secondStart = currentTime(videoElement);
        page.waitForTimeout(3000);
        double secondEnd = currentTime(videoElement);
        boolean secondEnded = ended(videoElement);

        System.out.println("[PLAYBACK 2] " + secondStart + " -> " + secondEnd + " (ended=" + secondEnded + ")");

        int readyState = readyState(videoElement);
        int videoWidth = ((Number) videoElement.evaluate("element => element.videoWidth")).intValue();
        int videoHeight = ((Number) videoElement.evaluate("element => element.videoHeight")).intValue();
        boolean validDimensions = videoWidth > 0 && videoHeight > 0;

        boolean videoAvailable =
                readyState >= 2 &&
                validDimensions &&
                progressed(secondStart, secondEnd) &&
                !secondEnded;

        // Best-effort pause before closing; a failure here must not flip a PASS to FAIL.
        try {
            page.getByLabel("Pause clip").click(new Locator.ClickOptions().setTimeout(3000));
        } catch (Exception ignored) {
        }

        if (videoAvailable) {
            System.out.println("=================================================");
            System.out.println("[PASS] VIDEO IS AVAILABLE AND PLAYING");
            System.out.println("=================================================");
            System.out.println("[PASS] Resumed playback progressed from " + secondStart + " to " + secondEnd);
            closeDetailOverlay();
            return true;
        }

        System.err.println("=================================================");
        System.err.println("[FAIL] VIDEO IS NOT AVAILABLE / NOT PLAYING");
        System.err.println("=================================================");
        System.err.println("Video Source : " + videoSource);
        System.err.println("Ready State  : " + readyState);
        System.err.println("Resolution   : " + videoWidth + "x" + videoHeight);
        System.err.println("Resume Start : " + secondStart);
        System.err.println("Resume End   : " + secondEnd);
        System.err.println("Ended        : " + secondEnded);

        captureVideoFailureScreenshot(screenshotPath);
        return false;

      } catch (Exception exception) {

        System.err.println("=================================================");
        System.err.println("[VIDEO VALIDATION ERROR]");
        System.err.println(exception.getMessage());
        System.err.println("=================================================");

        exception.printStackTrace();

        captureVideoFailureScreenshot(screenshotPath);

        return false;

      } finally {
        closeDetailOverlay();
      }
    }

    /** G. Clicks the play overlay via the preferred locator, falling back to the play icon. */
    private boolean clickPlayOverlay(Locator playOverlay, Locator playIcon) {
        try {
            if (playOverlay.count() > 0) {
                playOverlay.first().click(new Locator.ClickOptions().setTimeout(8000));
                return true;
            }
        } catch (Exception ignored) {
            // Fall through to the play-icon locator.
        }
        try {
            if (playIcon.count() > 0) {
                playIcon.first().click(new Locator.ClickOptions().setTimeout(8000));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * H. Waits for the actual media element to appear after the play overlay is clicked.
     *
     * <p>N. Falls back to a page-wide {@code video} search (not just inside the detail overlay)
     * in case the player portals its element elsewhere in the DOM, before giving up.
     */
    private Locator waitForVideoAfterPlay(Locator overlay) {
        Locator scoped = overlay.locator("video");
        try {
            scoped.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(10000));
            return scoped.first();
        } catch (Exception ignored) {
            // Fall back to a page-wide search below.
        }

        Locator pageWide = page.locator("video");
        try {
            pageWide.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5000));
            System.out.println("[INFO] Video element found page-wide (not nested under the detail overlay).");
            return pageWide.first();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Starts playback, preferring the app's own "Play clip" control over a raw JS play(). */
    private boolean ensurePlaying(Locator videoElement) {
        System.out.println("[INFO] Starting video playback...");
        try {
            Locator playClip = page.getByLabel("Play clip");
            if (playClip.count() > 0 && playClip.first().isVisible()) {
                playClip.first().click(new Locator.ClickOptions().setTimeout(5000));
            } else {
                videoElement.evaluate("element => element.play()");
            }
        } catch (Exception ignored) {
            try {
                videoElement.evaluate("element => element.play()");
            } catch (Exception e) {
                return false;
            }
        }
        page.waitForTimeout(2000);
        return true;
    }

    private boolean clickClipControl(String label) {
        try {
            Locator control = page.getByLabel(label);
            if (control.count() == 0) {
                return false;
            }
            control.first().click(new Locator.ClickOptions().setTimeout(5000));
            return true;
        } catch (Exception exception) {
            System.err.println("[WARN] Could not click '" + label + "': " + exception.getMessage());
            return false;
        }
    }

    /** Polls up to 5s for {@code element.paused} to reach the expected value. */
    private boolean waitForPaused(Locator videoElement, boolean expectPaused) {
        for (int i = 0; i < 10; i++) {
            if (paused(videoElement) == expectPaused) {
                return true;
            }
            page.waitForTimeout(500);
        }
        return paused(videoElement) == expectPaused;
    }

    private double currentTime(Locator videoElement) {
        return ((Number) videoElement.evaluate("element => element.currentTime")).doubleValue();
    }

    private boolean paused(Locator videoElement) {
        return (Boolean) videoElement.evaluate("element => element.paused");
    }

    private boolean ended(Locator videoElement) {
        return (Boolean) videoElement.evaluate("element => element.ended");
    }

    private int readyState(Locator videoElement) {
        return ((Number) videoElement.evaluate("element => element.readyState")).intValue();
    }

    // A plain end > start check misreads a short, looping clip as stalled: once playback wraps
    // back to 0 within the observation window, end lands lower than start even though the video
    // never stopped. Any material change - forward or wrapped-around backward - is evidence
    // playback is actually advancing.
    private boolean progressed(double start, double end) {
        return Math.abs(end - start) > 0.2;
    }

    private void closeDetailOverlay() {
        try {
            Locator closeButton = page.getByLabel("Close slider popup");
            if (closeButton.count() > 0 && closeButton.first().isVisible()) {
                closeButton.first().click();
                System.out.println("[INFO] Alert detail overlay closed.");
            }
        } catch (Exception e) {
            System.out.println("[INFO] Could not close alert detail overlay: " + e.getMessage());
        }
    }

    private void captureVideoFailureScreenshot(Path screenshotPath) {
        try {
            Files.createDirectories(screenshotPath.getParent());
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));
            System.out.println("[SCREENSHOT] Video failure saved to " + screenshotPath);
        } catch (Exception exception) {
            System.err.println("[SCREENSHOT FAILURE] Could not save video failure screenshot: "
                    + exception.getMessage());
        }
    }

}
