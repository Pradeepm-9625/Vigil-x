package com.vigilx.apisecurity.security;

import java.util.List;

/**
 * One OWASP API Security Top 10 category's set of concrete checks. Every implementation must never
 * run a destructive or high-volume action - see each class's own doc comment for exactly what it
 * does and does not execute.
 */
public interface SecurityCheck {

    OwaspCategory category();

    /** Never throws: any internal problem becomes a {@code NOT_ASSESSED} finding, not an exception. */
    List<SecurityFinding> run(SecurityCheckContext context);
}
