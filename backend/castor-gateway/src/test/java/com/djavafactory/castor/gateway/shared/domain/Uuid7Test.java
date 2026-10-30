package com.djavafactory.castor.gateway.shared.domain;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Uuid7Test {

    private static final Instant AT = Instant.parse("2026-10-29T08:20:00.123Z");

    @Test
    void hasVersion7AndTheRfc9562Variant() {
        UUID id = Uuid7.generate(AT, new Random(1));

        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        assertTrue(id.toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    }

    @Test
    void carriesTheTimeInMilliseconds() {
        assertEquals(AT, Uuid7.timeOf(Uuid7.generate(AT, new Random(2))));
    }

    @Test
    void laterIdsSortLater() {
        var random = new Random(3);
        UUID earlier = Uuid7.generate(AT, random);
        UUID later = Uuid7.generate(AT.plusMillis(1), random);

        // The text form sorts like the time; the database orders uuid values bytewise, which is the same order
        assertTrue(earlier.toString().compareTo(later.toString()) < 0);
    }

    @Test
    void sameTimeStillGivesDifferentIds() {
        var random = new Random(4);

        assertNotEquals(Uuid7.generate(AT, random), Uuid7.generate(AT, random));
    }

    @Test
    void theGeneratorNeverGoesBackwardsInsideOneMillisecond() {
        var generator = new Uuid7.Generator(new Random(5));
        UUID previous = generator.next(AT);

        for (int i = 0; i < 3000; i++) {
            UUID next = generator.next(AT);
            assertTrue(previous.toString().compareTo(next.toString()) < 0, i + ": " + previous + " then " + next);
            assertEquals(7, next.version());
            previous = next;
        }
    }

    @Test
    void theGeneratorNeverGoesBackwardsWhenTheClockSteps() {
        var generator = new Uuid7.Generator(new Random(6));
        UUID first = generator.next(AT);
        UUID second = generator.next(AT.minusSeconds(60));
        UUID third = generator.next(AT.plusSeconds(60));

        assertTrue(first.toString().compareTo(second.toString()) < 0);
        assertTrue(second.toString().compareTo(third.toString()) < 0);
        assertEquals(AT.plusSeconds(60), Uuid7.timeOf(third));
    }

    @Test
    void theGeneratorFollowsTheClockWhenItMovesOn() {
        var generator = new Uuid7.Generator(new Random(7));

        assertEquals(AT, Uuid7.timeOf(generator.next(AT)));
        assertEquals(AT.plusMillis(5), Uuid7.timeOf(generator.next(AT.plusMillis(5))));
    }

    @Test
    void timeOfRefusesOtherVersions() {
        assertThrows(IllegalArgumentException.class, () -> Uuid7.timeOf(UUID.randomUUID()));
    }
}
