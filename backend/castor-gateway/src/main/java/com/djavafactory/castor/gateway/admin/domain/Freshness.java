package com.djavafactory.castor.gateway.admin.domain;

import java.time.Duration;
import java.time.Instant;

/** Rule R3: how old the last thing a source sent is, against what that source may take. Never observed is UNKNOWN. */
public enum Freshness {
    FRESH,
    STALE,
    UNKNOWN;

    /**
     * @param lastReceivedAt when the source last sent a record, null when it never did
     * @param sla the longest accepted age
     */
    public static Freshness of(Instant lastReceivedAt, Instant now, Duration sla) {
        if (lastReceivedAt == null) {
            return UNKNOWN;
        }
        return Duration.between(lastReceivedAt, now).compareTo(sla) <= 0 ? FRESH : STALE;
    }
}
