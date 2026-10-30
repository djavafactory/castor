package com.djavafactory.castor.gateway.normalize.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.djavafactory.castor.contracts.CanonicalEvent;

/**
 * eventId is the name-based UUID of (receptionId, index): the same record normalized again gives the same ids, which
 * is how the consumer (STATE-03) recognises a message the relay sent twice.
 */
public final class EventIds {

    private EventIds() {}

    public static UUID of(UUID receptionId, int index) {
        return UUID.nameUUIDFromBytes((receptionId + "#" + index).getBytes(StandardCharsets.UTF_8));
    }

    /** The event with the eventId of its place in the record's output. */
    public static CanonicalEvent stamp(CanonicalEvent event, UUID receptionId, int index) {
        return new CanonicalEvent(
                of(receptionId, index).toString(),
                event.schemaVersion(),
                event.portId(),
                event.sourceSystem(),
                event.sourceEventId(),
                event.sourceSequence(),
                event.receptionId(),
                event.type(),
                event.entityType(),
                event.entityId(),
                event.aggregateId(),
                event.occurredAt(),
                event.receivedAt(),
                event.correlationId(),
                event.quality(),
                event.payload());
    }
}
