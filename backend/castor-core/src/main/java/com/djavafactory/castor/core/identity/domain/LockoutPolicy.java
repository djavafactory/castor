package com.djavafactory.castor.core.identity.domain;

import java.time.Duration;
import java.time.Instant;

/** After maxFailures wrong passwords within window the account is locked for the same window. */
public record LockoutPolicy(int maxFailures, Duration window) {

    public LockoutPolicy {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be at least 1");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }

    /** The failure count after one more failure: a failure older than the window starts a new count. */
    public int nextCount(int current, Instant lastFailure, Instant now) {
        boolean withinWindow = lastFailure != null && lastFailure.isAfter(now.minus(window));
        return withinWindow ? current + 1 : 1;
    }

    public boolean locks(int count) {
        return count >= maxFailures;
    }

    public Instant lockedUntil(Instant now) {
        return now.plus(window);
    }

    public static boolean isLocked(Instant lockedUntil, Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
