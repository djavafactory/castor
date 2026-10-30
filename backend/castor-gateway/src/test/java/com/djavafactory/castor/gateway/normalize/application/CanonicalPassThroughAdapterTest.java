package com.djavafactory.castor.gateway.normalize.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.CanonicalEventValidator;
import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.gateway.normalize.domain.EventIds;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.shared.domain.Json;
import com.djavafactory.castor.gateway.support.Events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalPassThroughAdapterTest {

    private static final UUID RECEPTION = UUID.fromString("019bd4a0-0000-7000-8000-000000000002");
    private static final Instant RECEIVED = Instant.parse("2026-10-29T08:20:01.250Z");

    private final CanonicalPassThroughAdapter adapter =
            new CanonicalPassThroughAdapter(new CanonicalEventValidator(), new CanonicalEventCodec());

    private static InboxRecord record(String portId, String json) throws Exception {
        JsonNode raw = Json.parse(json);
        return new InboxRecord(
                RECEPTION, portId, "canonical", raw.path("sourceEventId").asText(), RECEIVED, raw);
    }

    private Rejection rejected(InboxRecord record) {
        return assertThrows(Rejection.class, () -> adapter.normalize(record));
    }

    @Test
    void servesTheCanonicalSource() {
        assertEquals("canonical", adapter.source());
    }

    @Test
    void setsTheFieldsTheGatewayOwnsAndKeepsTheRest() throws Exception {
        List<CanonicalEvent> events = adapter.normalize(record("westports", Events.status("EV-1")));

        assertEquals(1, events.size());
        CanonicalEvent event = events.get(0);
        assertEquals(EventIds.of(RECEPTION, 0).toString(), event.eventId());
        assertEquals(RECEPTION.toString(), event.receptionId());
        assertEquals(RECEIVED, event.receivedAt());
        assertEquals("westports", event.portId());
        assertEquals("EV-1", event.sourceEventId());
        assertEquals("TEST", event.sourceSystem());
        assertEquals("crane-mhc-2", event.aggregateId());
        assertEquals(Quality.SIMULATED, event.quality());
        assertEquals("BLOCKED", event.payload().get("status").asText());
        assertEquals(Instant.parse("2026-10-29T08:20:00Z"), event.occurredAt());
    }

    @Test
    void whatTheSenderWroteInTheGatewayFieldsIsReplaced() throws Exception {
        String json = Events.status("EV-2")
                .replace(
                        "\"schemaVersion\": 1,",
                        "\"schemaVersion\": 1, \"eventId\": \"11111111-1111-1111-1111-111111111111\","
                                + " \"receptionId\": \"forged\", \"receivedAt\": \"2001-01-01T00:00:00Z\","
                                + " \"portId\": \"westports\",");

        CanonicalEvent event = adapter.normalize(record("westports", json)).get(0);

        assertEquals(EventIds.of(RECEPTION, 0).toString(), event.eventId());
        assertEquals(RECEPTION.toString(), event.receptionId());
        assertEquals(RECEIVED, event.receivedAt());
    }

    @Test
    void anEventForAnotherPortIsRefused() throws Exception {
        String json =
                Events.status("EV-3").replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"portId\": \"other\",");

        Rejection rejection = rejected(record("westports", json));

        assertEquals(Rejection.PORT_MISMATCH, rejection.reasonCode());
        assertTrue(rejection.getMessage().contains("other")
                && rejection.getMessage().contains("westports"));
    }

    @Test
    void anInvalidPayloadIsRefusedWithItsPath() throws Exception {
        Rejection rejection = rejected(record("westports", Events.invalidStatus("EV-4")));

        assertEquals(Rejection.SCHEMA_INVALID, rejection.reasonCode());
        assertTrue(rejection.getMessage().contains("/payload/status"), rejection.getMessage());
    }

    @Test
    void aMissingEnvelopeFieldIsRefused() throws Exception {
        Rejection rejection =
                rejected(record("westports", Events.status("EV-5").replace("\"quality\": \"SIMULATED\",", "")));

        assertEquals(Rejection.SCHEMA_INVALID, rejection.reasonCode());
        assertTrue(rejection.getMessage().contains("quality"), rejection.getMessage());
    }

    @Test
    void anUnknownTypeIsRefused() throws Exception {
        Rejection rejection = rejected(
                record("westports", Events.status("EV-6").replace("equipment.status.changed", "equipment.exploded")));

        assertEquals(Rejection.SCHEMA_INVALID, rejection.reasonCode());
        assertTrue(rejection.getMessage().contains("Unknown event type"), rejection.getMessage());
    }

    @Test
    void manyViolationsAreSummarised() throws Exception {
        // Every required field of the envelope missing
        String json = "{\"sourceEventId\":\"EV-7\",\"occurredAt\":\"2026-10-29T08:20:00Z\"}";

        Rejection rejection = rejected(record("westports", json));

        assertEquals(Rejection.SCHEMA_INVALID, rejection.reasonCode());
        assertTrue(rejection.getMessage().matches(".*; and [0-9]+ more"), rejection.getMessage());
    }

    @Test
    void aRecordThatIsNotAnObjectIsRefused() throws Exception {
        var record = new InboxRecord(RECEPTION, "westports", "canonical", "EV-8", RECEIVED, Json.parse("[1]"));

        assertEquals(Rejection.SCHEMA_INVALID, rejected(record).reasonCode());
        assertEquals(
                Rejection.SCHEMA_INVALID,
                rejected(new InboxRecord(RECEPTION, "westports", "canonical", "EV-9", RECEIVED, null))
                        .reasonCode());
    }
}
