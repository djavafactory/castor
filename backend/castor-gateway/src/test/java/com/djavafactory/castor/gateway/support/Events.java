package com.djavafactory.castor.gateway.support;

import java.util.UUID;

/** JSON bodies for the intake endpoint. */
public final class Events {

    public static final String PORT = "westports";
    public static final String EVENTS_PATH = "/api/v1/ports/" + PORT + "/integrations/canonical/events";

    private Events() {}

    /** A fresh sourceEventId, so tests that share one Kafka topic can tell their messages apart. */
    public static String newSourceEventId() {
        return "it-" + UUID.randomUUID();
    }

    /**
     * A valid canonical equipment.status.changed event as a source sends it: without the fields the gateway owns
     * (eventId, receptionId, receivedAt) and without portId.
     */
    public static String status(String sourceEventId, String aggregateId, String occurredAt) {
        return """
                {
                  "schemaVersion": 1,
                  "sourceSystem": "TEST",
                  "sourceEventId": "%s",
                  "type": "equipment.status.changed",
                  "entityType": "EQUIPMENT",
                  "entityId": "%s",
                  "aggregateId": "%s",
                  "occurredAt": "%s",
                  "quality": "SIMULATED",
                  "payload": { "status": "BLOCKED", "reason": "Waiting for trucks" }
                }""".formatted(sourceEventId, aggregateId, aggregateId, occurredAt);
    }

    public static String status(String sourceEventId) {
        return status(sourceEventId, "crane-mhc-2", "2026-10-29T08:20:00Z");
    }

    /** A record that passes intake (sourceEventId and occurredAt) but is not a valid canonical event. */
    public static String invalidStatus(String sourceEventId) {
        return status(sourceEventId).replace("\"BLOCKED\"", "\"BROKEN\"");
    }
}
