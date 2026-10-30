package com.djavafactory.castor.core.operations.domain;

import java.time.Duration;
import java.time.Instant;

/** How old an observed value is. UNKNOWN is a value never observed: missing is not "fresh" and not 0. */
public enum Freshness {
    FRESH,
    AGING,
    STALE,
    UNKNOWN;

    /** FRESH up to {@code freshFor}, AGING up to {@code staleAfter}, STALE beyond; a future observation is FRESH. */
    public static Freshness of(Instant observedAt, Instant now, Duration freshFor, Duration staleAfter) {
        if (freshFor.compareTo(staleAfter) > 0) {
            throw new IllegalArgumentException("freshFor " + freshFor + " is longer than staleAfter " + staleAfter);
        }
        if (observedAt == null) {
            return UNKNOWN;
        }
        Duration age = Duration.between(observedAt, now);
        if (age.compareTo(freshFor) <= 0) {
            return FRESH;
        }
        return age.compareTo(staleAfter) <= 0 ? AGING : STALE;
    }
}
