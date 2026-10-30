package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;

/**
 * The stale-event rule (README "Decisions: event ordering and reconciliation"). Kafka orders events per aggregate only,
 * and two sources may describe one machine, so an event is compared with the row it would change.
 */
public final class EventOrder {

    private EventOrder() {}

    /**
     * True when the event is older than the state already held. From the same source and with a sequence on both sides
     * the sequence decides (lower is older); in every other case the occurrence time decides (earlier is older). Equal
     * is not older.
     */
    public static boolean isStale(
            String rowSource,
            Long rowSequence,
            Instant rowOccurredAt,
            String eventSource,
            Long eventSequence,
            Instant eventOccurredAt) {
        if (rowSequence != null && eventSequence != null && rowSource != null && rowSource.equals(eventSource)) {
            return eventSequence < rowSequence;
        }
        return eventOccurredAt.isBefore(rowOccurredAt);
    }
}
