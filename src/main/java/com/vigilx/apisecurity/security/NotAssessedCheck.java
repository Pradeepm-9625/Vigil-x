package com.vigilx.apisecurity.security;

import java.util.List;

import com.vigilx.apisecurity.security.SecurityFinding.Severity;
import com.vigilx.apisecurity.security.SecurityFinding.Verdict;

/**
 * A category with no meaningful, safe, non-destructive automated check available for this
 * application today. Recorded explicitly as {@code NOT_ASSESSED} with the concrete reason - per the
 * brief's own rule that a category must never be marked PASS without an actual test behind it - three
 * static instances below cover API5, API6 and API10.
 */
public final class NotAssessedCheck implements SecurityCheck {

    private final OwaspCategory category;
    private final String reason;

    private NotAssessedCheck(OwaspCategory category, String reason) {
        this.category = category;
        this.reason = reason;
    }

    /** API5 - Broken Function Level Authorization: needs a second, lower-privileged role account. */
    public static NotAssessedCheck brokenFunctionLevelAuthorization() {
        return new NotAssessedCheck(OwaspCategory.API5_BROKEN_FUNCTION_AUTHORIZATION,
                "Meaningfully testing whether a lower-privileged role is blocked from an admin-only "
                        + "function requires a second account with a lower role. Only one admin-role "
                        + "account is configured. Provide a second, lower-privileged account to enable "
                        + "this check.");
    }

    /** API6 - Unrestricted Access to Sensitive Business Flows: real side effects, not auto-triggered. */
    public static NotAssessedCheck sensitiveBusinessFlows() {
        return new NotAssessedCheck(OwaspCategory.API6_SENSITIVE_BUSINESS_FLOWS,
                "Endpoints in this category (forgot-password, OTP verification, license activation, "
                        + "password reset) have real side effects - sending a real email/OTP or consuming "
                        + "a real license activation - and are not auto-triggered by this framework. "
                        + "Exercising them requires an explicit, separately-approved run against "
                        + "disposable test data.");
    }

    /** API10 - Unsafe Consumption of APIs: needs knowledge of the app's own downstream integrations. */
    public static NotAssessedCheck unsafeConsumption() {
        return new NotAssessedCheck(OwaspCategory.API10_UNSAFE_CONSUMPTION,
                "Assessing how safely this application consumes its own downstream/third-party APIs "
                        + "(the license server, the NVR/storage backend, etc.) requires visibility into "
                        + "or control of those integrations, which black-box HTTP testing from the client "
                        + "side does not provide.");
    }

    @Override
    public OwaspCategory category() {
        return category;
    }

    @Override
    public List<SecurityFinding> run(SecurityCheckContext context) {
        return List.of(new SecurityFinding(SecurityHttp.now(), category, "No automated check available",
                null, null, Verdict.NOT_ASSESSED, Severity.INFO, reason, null));
    }
}
