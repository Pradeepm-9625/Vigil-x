package com.vigilx.apisecurity.tools;

import com.vigilx.apisecurity.execution.ApiAuthClient;
import com.vigilx.config.ConfigReader;

/**
 * Throwaway probe: checks only whether the configured account can currently log in via the real
 * {@code /auth/login} endpoint - nothing else. Written to confirm whether the account-lockout found
 * during an earlier load-test run (triggered by that run replaying a captured login attempt
 * repeatedly) has since cleared, before attempting anything else that depends on being logged in.
 */
public final class LoginUnlockProbe {
    private LoginUnlockProbe() {
    }

    public static void main(String[] args) {
        String scheme = ConfigReader.getOrDefault("apisecurity.restassured.scheme", "http");
        String host = ConfigReader.getOrDefault("apisecurity.auth.host", "");
        if (host.isBlank()) {
            String base = ConfigReader.getOrDefault("base.url", "");
            host = base.replaceFirst("^https?://", "").replaceFirst("/$", "") + ":3000";
        }
        try {
            String token = ApiAuthClient.login(scheme, host);
            System.out.println("PROBE: login SUCCEEDED, token length=" + token.length());
            System.out.println("PROBE: RESULT = UNLOCKED");
        } catch (Exception exception) {
            System.out.println("PROBE: login FAILED - " + exception.getMessage());
            System.out.println("PROBE: RESULT = STILL LOCKED / FAILED");
        }
    }
}
