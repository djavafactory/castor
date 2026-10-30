package com.djavafactory.castor.gateway.normalize.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EventIdsTest {

    private static final UUID RECEPTION = UUID.fromString("019bd4a0-0000-7000-8000-000000000001");

    @Test
    void theSameRecordAndIndexAlwaysGiveTheSameId() {
        assertEquals(EventIds.of(RECEPTION, 0), EventIds.of(RECEPTION, 0));
    }

    @Test
    void anotherIndexOrRecordGivesAnotherId() {
        assertNotEquals(EventIds.of(RECEPTION, 0), EventIds.of(RECEPTION, 1));
        assertNotEquals(EventIds.of(RECEPTION, 0), EventIds.of(UUID.randomUUID(), 0));
    }

    @Test
    void stampReplacesOnlyTheEventId() {
        var event = new CanonicalEvent(
                "00000000-0000-0000-0000-000000000000",
                1,
                "westports",
                "TEST",
                "EV-1",
                7L,
                RECEPTION.toString(),
                "equipment.status.changed",
                "EQUIPMENT",
                "crane-1",
                "crane-1",
                Instant.parse("2026-10-29T08:00:00Z"),
                Instant.parse("2026-10-29T08:00:01Z"),
                "corr-1",
                Quality.SIMULATED,
                JsonNodeFactory.instance.objectNode().put("status", "IDLE"));

        CanonicalEvent stamped = EventIds.stamp(event, RECEPTION, 3);

        assertEquals(EventIds.of(RECEPTION, 3).toString(), stamped.eventId());
        assertEquals(
                event,
                new CanonicalEvent(
                        event.eventId(),
                        stamped.schemaVersion(),
                        stamped.portId(),
                        stamped.sourceSystem(),
                        stamped.sourceEventId(),
                        stamped.sourceSequence(),
                        stamped.receptionId(),
                        stamped.type(),
                        stamped.entityType(),
                        stamped.entityId(),
                        stamped.aggregateId(),
                        stamped.occurredAt(),
                        stamped.receivedAt(),
                        stamped.correlationId(),
                        stamped.quality(),
                        stamped.payload()));
    }
}
