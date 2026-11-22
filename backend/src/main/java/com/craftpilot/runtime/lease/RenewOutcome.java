package com.craftpilot.runtime.lease;

public enum RenewOutcome {
    /** lease_until extended. */
    RENEWED,
    /** Owner/epoch no longer match, the lease already expired, or the run left the active states. */
    LOST,
    /** Still owned, but execution_deadline has passed: stop renewing and let the loop fail with DEADLINE. */
    DEADLINE
}
