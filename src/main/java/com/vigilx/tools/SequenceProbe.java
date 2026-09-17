package com.vigilx.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.vigilx.config.ConfigReader;
import com.vigilx.factory.PlaywrightFactory;
import com.vigilx.pages.LoginPage;
import com.vigilx.pages.SequencePage;

public final class SequenceProbe {
    private SequenceProbe() {
    }

    public static void main(String[] args) throws Exception {
        Page page = PlaywrightFactory.initializeBrowser();
        try {
            page.navigate(ConfigReader.get("base.url"));
            new LoginPage(page).login(ConfigReader.get("username"), ConfigReader.get("password"));
            SequencePage sequence = new SequencePage(page);
            boolean navigated = sequence.navigateToSequence();
            System.out.println("PROBE: navigated=" + navigated);

            Locator addSeq = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Add sequence").setExact(false)).first();
            addSeq.click();
            page.waitForTimeout(1500);

            int dialogCount = page.getByRole(AriaRole.DIALOG).count();
            System.out.println("PROBE: dialog count after 'Add sequence' click = " + dialogCount);
            for (int i = 0; i < dialogCount; i++) {
                Locator d = page.getByRole(AriaRole.DIALOG).nth(i);
                System.out.println("PROBE: dialog[" + i + "] text (first 200 chars) = "
                        + safe(d.innerText(), 200));
            }

            // Fill name
            Locator field = page.getByRole(AriaRole.TEXTBOX,
                    new Page.GetByRoleOptions().setName("Sequence Name").setExact(true)).first();
            System.out.println("PROBE: name field visible = " + field.isVisible());
            field.click();
            field.fill("Probe Sequence " + System.currentTimeMillis());

            // Select a camera the same way createSequence does
            boolean camOk = sequence.addCamera("VMS SMART CITY SURVILIEN...", "Khammam MC 1 Office Desk", "Sattupalli K-2");
            System.out.println("PROBE: camera selected = " + camOk);
            page.waitForTimeout(1000);

            dialogCount = page.getByRole(AriaRole.DIALOG).count();
            System.out.println("PROBE: dialog count after camera selection = " + dialogCount);
            for (int i = 0; i < dialogCount; i++) {
                Locator d = page.getByRole(AriaRole.DIALOG).nth(i);
                System.out.println("PROBE: dialog[" + i + "] text (first 300 chars) = "
                        + safe(d.innerText(), 300));
            }

            // Dump EVERY button inside the dialog itself, unconditionally (not filtered), so the
            // real footer submit control's exact accessible name is visible no matter what it is.
            Locator dialog = page.getByRole(AriaRole.DIALOG).first();
            Locator dialogButtons = dialog.getByRole(AriaRole.BUTTON);
            int dCount = dialogButtons.count();
            System.out.println("PROBE: total buttons inside dialog = " + dCount);
            for (int i = 0; i < dCount; i++) {
                Locator b = dialogButtons.nth(i);
                String name;
                try {
                    name = b.innerText();
                } catch (Exception e) {
                    name = "<err>";
                }
                boolean visible = false;
                boolean enabled = false;
                try {
                    visible = b.isVisible();
                } catch (Exception ignored) {
                }
                try {
                    enabled = b.isEnabled();
                } catch (Exception ignored) {
                }
                System.out.println("PROBE: dialogButton[" + i + "] text=\"" + name + "\" visible=" + visible
                        + " enabled=" + enabled);
            }

            page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("target/probe-sequence-create.png")).setFullPage(true));
            System.out.println("PROBE: screenshot saved to target/probe-sequence-create.png");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PlaywrightFactory.closeBrowser();
        }
    }

    private static String safe(String s, int max) {
        if (s == null) {
            return "null";
        }
        String flat = s.replace("\n", " | ");
        return flat.length() > max ? flat.substring(0, max) : flat;
    }
}
