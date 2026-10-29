package com.djavafactory.castor.contracts;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalEventCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CanonicalEventCodec codec = new CanonicalEventCodec();

    @Test
    void encodeThenDecodeKeepsEveryField() throws Exception {
        JsonNode payload = codec.readTree("{\"status\":\"DOWN\",\"reason\":\"Hydraulic leak\",\"n\":12.30}");
        CanonicalEvent event = new CanonicalEvent(
                "3f8e5a52-1c1b-4b57-9a0e-2f2d3a6c9b10",
                CanonicalEvent.SCHEMA_VERSION,
                "westports",
                "ITAP",
                "ITAP-EQ-1",
                42L,
                "rec-1",
                "equipment.status.changed",
                "EQUIPMENT",
                "crane-1",
                "crane-1",
                Instant.parse("2026-10-29T08:15:30.123456789Z"),
                Instant.parse("2026-10-29T08:15:31Z"),
                "corr-1",
                Quality.INFERRED,
                payload);

        CanonicalEvent decoded = codec.decode(codec.encode(event));

        assertEquals(event.eventId(), decoded.eventId());
        assertEquals(1, decoded.schemaVersion());
        assertEquals(event.portId(), decoded.portId());
        assertEquals(event.sourceSystem(), decoded.sourceSystem());
        assertEquals(event.sourceEventId(), decoded.sourceEventId());
        assertEquals(42L, decoded.sourceSequence());
        assertEquals(event.receptionId(), decoded.receptionId());
        assertEquals(event.type(), decoded.type());
        assertEquals(event.entityType(), decoded.entityType());
        assertEquals(event.entityId(), decoded.entityId());
        assertEquals(event.aggregateId(), decoded.aggregateId());
        assertEquals(event.occurredAt(), decoded.occurredAt());
        assertEquals(event.receivedAt(), decoded.receivedAt());
        assertEquals("corr-1", decoded.correlationId());
        assertEquals(Quality.INFERRED, decoded.quality());
        assertEquals(payload, decoded.payload());
        assertEquals(event, decoded);
    }

    @Test
    void absentOptionalFieldsStayAbsent() {
        CanonicalEvent event = new CanonicalEvent(
                "3f8e5a52-1c1b-4b57-9a0e-2f2d3a6c9b10",
                1,
                "westports",
                "CBAS",
                "S-1",
                null,
                "rec-1",
                "gate.transaction",
                "TRUCK_VISIT",
                "v-1",
                "gate-1",
                Instant.parse("2026-10-29T08:15:30Z"),
                Instant.parse("2026-10-29T08:15:31Z"),
                null,
                Quality.OBSERVED,
                JSON.createObjectNode());

        String json = codec.encode(event);

        assertFalse(json.contains("sourceSequence"));
        assertFalse(json.contains("correlationId"));
        CanonicalEvent decoded = codec.decode(json);
        assertNull(decoded.sourceSequence());
        assertNull(decoded.correlationId());
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyValidExampleRoundTripsWithoutLoss(EventType type) throws Exception {
        String example = Examples.valid(type);
        CanonicalEvent decoded = codec.decode(example);

        String again = codec.encode(decoded);

        assertEquals(codec.decode(again), decoded);
        assertEquals(codec.readTree(example), codec.readTree(again), "the encoded event differs from the example");
        assertEquals(decoded, codec.decode(codec.encodeBytes(decoded)));
    }

    @Test
    void decodeIgnoresFieldsAddedLater() {
        String json = Examples.valid(EventType.GATE_TRANSACTION)
                .replace("{\n  \"eventId\"", "{\n  \"addedLater\": 1,\n  \"eventId\"");

        assertEquals("gate.transaction", codec.decode(json).type());
    }

    @Test
    void decodeRejectsTextThatIsNotAnEvent() {
        assertThrows(CanonicalEventException.class, () -> codec.decode("{not json"));
        assertThrows(CanonicalEventException.class, () -> codec.decode("{\"quality\":\"GUESSED\"}"));
    }
}
