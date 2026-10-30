package com.djavafactory.castor.gateway.admin.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreshnessTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    private static final Duration SLA = Duration.ofMinutes(15);

    @Test
    void neverReceivedIsUnknownNotStale() {
        assertEquals(Freshness.UNKNOWN, Freshness.of(null, NOW, SLA));
    }

    @Test
    void exactlyTheSlaIsStillFreshAndOneSecondMoreIsStale() {
        assertEquals(Freshness.FRESH, Freshness.of(NOW.minus(SLA), NOW, SLA));
        assertEquals(Freshness.STALE, Freshness.of(NOW.minus(SLA).minusSeconds(1), NOW, SLA));
        assertEquals(Freshness.FRESH, Freshness.of(NOW, NOW, SLA));
    }
}
