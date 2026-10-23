package it.replica8.castor.core.identity.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockoutPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private final LockoutPolicy policy = new LockoutPolicy(5, Duration.ofMinutes(15));

    @Test
    void failuresWithinTheWindowAccumulate() {
        assertEquals(3, policy.nextCount(2, NOW.minusSeconds(60), NOW));
    }

    @Test
    void aFailureOlderThanTheWindowStartsANewCount() {
        assertEquals(1, policy.nextCount(4, NOW.minus(Duration.ofMinutes(16)), NOW));
        assertEquals(1, policy.nextCount(0, null, NOW));
    }

    @Test
    void locksAtTheMaximum() {
        assertFalse(policy.locks(4));
        assertTrue(policy.locks(5));
        assertEquals(NOW.plus(Duration.ofMinutes(15)), policy.lockedUntil(NOW));
    }

    @Test
    void lockEndsWhenTheTimePasses() {
        assertTrue(LockoutPolicy.isLocked(NOW.plusSeconds(1), NOW));
        assertFalse(LockoutPolicy.isLocked(NOW, NOW));
        assertFalse(LockoutPolicy.isLocked(null, NOW));
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThrows(IllegalArgumentException.class, () -> new LockoutPolicy(0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new LockoutPolicy(5, Duration.ZERO));
    }
}
