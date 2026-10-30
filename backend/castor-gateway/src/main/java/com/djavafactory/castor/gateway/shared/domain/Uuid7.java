package com.djavafactory.castor.gateway.shared.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * UUID version 7 (RFC 9562): 48 bits of Unix time in milliseconds, then random bits. Ids made later sort later, which
 * is what the normalizer relies on to take the oldest inbox rows first. The time comes from the caller (rule R8).
 */
public final class Uuid7 {

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT = 0x8000_0000_0000_0000L;
    private static final int COUNTER_LIMIT = 0x1000;

    private Uuid7() {}

    /** An id for {@code time}. Ids made in the same millisecond have no defined order; use {@link Generator} for that. */
    public static UUID generate(Instant time, RandomGenerator random) {
        return of(time.toEpochMilli(), (int) (random.nextLong() & 0x0FFF), random);
    }

    /** The instant a version 7 id was made, to the millisecond. */
    public static Instant timeOf(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException("Not a version 7 UUID: " + id);
        }
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }

    private static UUID of(long millis, int counter, RandomGenerator random) {
        long msb = ((millis & 0xFFFF_FFFF_FFFFL) << 16) | VERSION_7 | counter;
        long lsb = VARIANT | (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL);
        return new UUID(msb, lsb);
    }

    /**
     * Makes ids that always increase, even inside one millisecond (RFC 9562 method 1: a counter in the 12 bits after
     * the time) and even if the clock stands still or steps back. The records of one request array therefore sort in
     * the order they were sent, which keeps the events of one aggregate in order through the pipeline.
     */
    public static final class Generator {

        private final RandomGenerator random;
        private long lastMillis = -1;
        private int counter;

        public Generator(RandomGenerator random) {
            this.random = random;
        }

        public synchronized UUID next(Instant time) {
            long millis = time.toEpochMilli();
            if (millis > lastMillis) {
                lastMillis = millis;
                // Start low, so a millisecond has room for a whole batch
                counter = random.nextInt(COUNTER_LIMIT / 4);
            } else if (++counter >= COUNTER_LIMIT) {
                lastMillis++;
                counter = 0;
            }
            return of(lastMillis, counter, random);
        }
    }
}
