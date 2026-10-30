package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventOrderTest {

    private static final Instant T = Instant.parse("2026-10-30T08:00:00Z");

    @Test
    void sameSourceWithSequencesTheLowerSequenceIsOlder() {
        assertTrue(EventOrder.isStale("CBAS", 10L, T, "CBAS", 9L, T.plusSeconds(99)));
        assertFalse(EventOrder.isStale("CBAS", 10L, T, "CBAS", 11L, T.minusSeconds(99)));
    }

    @Test
    void anEqualSequenceIsNotOlder() {
        assertFalse(EventOrder.isStale("CBAS", 10L, T, "CBAS", 10L, T));
    }

    @Test
    void anotherSourceIsJudgedByTheClockWhateverTheSequences() {
        assertTrue(EventOrder.isStale("CBAS", 10L, T, "EDIC", 99L, T.minusSeconds(1)));
        assertFalse(EventOrder.isStale("CBAS", 10L, T, "EDIC", 1L, T.plusSeconds(1)));
    }

    @Test
    void withoutASequenceOnEitherSideTheClockDecides() {
        assertTrue(EventOrder.isStale("S", null, T, "S", 5L, T.minusSeconds(1)));
        assertTrue(EventOrder.isStale("S", 5L, T, "S", null, T.minusSeconds(1)));
        assertFalse(EventOrder.isStale("S", null, T, "S", null, T));
        assertFalse(EventOrder.isStale("S", null, T, "S", null, T.plusSeconds(1)));
    }
}
