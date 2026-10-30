package com.djavafactory.castor.gateway.support;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The clock of every Quarkus test (it takes the place of the system clock, rule R8). It stands still until a test moves
 * it, so reception times, signature skew and retention are exact. {@link #reset()} puts it back at {@link #START}.
 */
@ApplicationScoped
public class TestClock implements InstantSource {

    public static final Instant START = Instant.parse("2026-10-29T08:00:00Z");

    private volatile Instant now = START;

    @Override
    public Instant instant() {
        return now;
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void reset() {
        now = START;
    }
}
