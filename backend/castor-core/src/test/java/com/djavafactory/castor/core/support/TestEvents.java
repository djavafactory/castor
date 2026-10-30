package com.djavafactory.castor.core.support;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.Quality;

/**
 * Canonical events for tests, published the way the gateway would (the contract codec). Every event gets a new id and
 * takes its time from the clock the builder was given, so a test controls the order of events by stepping that clock.
 */
public final class TestEvents {

    public static final String PORT = "westports";
    public static final String SOURCE = "SENSORS";

    private static final CanonicalEventCodec CODEC = new CanonicalEventCodec();

    private final InstantSource clock;
    private final AtomicLong counter = new AtomicLong();

    public TestEvents(InstantSource clock) {
        this.clock = clock;
    }

    /** A clock a test moves by hand. */
    public static final class SteppedClock implements InstantSource {

        private Instant now;

        public SteppedClock(Instant start) {
            this.now = start;
        }

        public Instant step(long seconds) {
            now = now.plusSeconds(seconds);
            return now;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public CanonicalEvent status(Object asset, String status, String reason) {
        ObjectNode payload = payload().put("status", status);
        if (reason != null) {
            payload.put("reason", reason);
        }
        return event("equipment.status.changed", "EQUIPMENT", asset.toString(), clock.instant(), null, payload);
    }

    public CanonicalEvent position(Object asset, double lon, double lat, Double heading, double accuracy) {
        ObjectNode payload = payload().put("lon", lon).put("lat", lat).put("accuracyM", accuracy);
        if (heading != null) {
            payload.put("headingDeg", heading);
        }
        return event("equipment.position.observed", "EQUIPMENT", asset.toString(), clock.instant(), null, payload);
    }

    public CanonicalEvent assignment(Object asset, String jobId, String vesselCallId) {
        ObjectNode payload = payload();
        if (jobId != null) {
            payload.put("jobId", jobId);
        }
        if (vesselCallId != null) {
            payload.put("vesselCallId", vesselCallId);
        }
        return event("equipment.assignment.changed", "EQUIPMENT", asset.toString(), clock.instant(), null, payload);
    }

    public CanonicalEvent vesselCall(String callCode, String status, Object berth) {
        ObjectNode payload = payload().put("callCode", callCode).put("vesselName", "MV Aurora");
        if (status != null) {
            payload.put("status", status);
        }
        if (berth != null) {
            payload.put("berthId", berth.toString());
        }
        return event("vessel.call.updated", "VESSEL_CALL", callCode, clock.instant(), null, payload);
    }

    public CanonicalEvent withPayload(CanonicalEvent e, ObjectNode payload) {
        return copy(e, e.type(), e.entityId(), e.occurredAt(), e.sourceSequence(), payload);
    }

    public CanonicalEvent at(CanonicalEvent e, Instant occurredAt) {
        return copy(e, e.type(), e.entityId(), occurredAt, e.sourceSequence(), (ObjectNode) e.payload());
    }

    public CanonicalEvent sequence(CanonicalEvent e, Long sequence) {
        return copy(e, e.type(), e.entityId(), e.occurredAt(), sequence, (ObjectNode) e.payload());
    }

    public CanonicalEvent type(CanonicalEvent e, String type) {
        return copy(e, type, e.entityId(), e.occurredAt(), e.sourceSequence(), (ObjectNode) e.payload());
    }

    public CanonicalEvent source(CanonicalEvent e, String source) {
        return new CanonicalEvent(
                e.eventId(),
                e.schemaVersion(),
                e.portId(),
                source,
                e.sourceEventId(),
                e.sourceSequence(),
                e.receptionId(),
                e.type(),
                e.entityType(),
                e.entityId(),
                e.aggregateId(),
                e.occurredAt(),
                e.receivedAt(),
                e.correlationId(),
                e.quality(),
                e.payload());
    }

    public CanonicalEvent port(CanonicalEvent e, String port) {
        return new CanonicalEvent(
                e.eventId(),
                e.schemaVersion(),
                port,
                e.sourceSystem(),
                e.sourceEventId(),
                e.sourceSequence(),
                e.receptionId(),
                e.type(),
                e.entityType(),
                e.entityId(),
                e.aggregateId(),
                e.occurredAt(),
                e.receivedAt(),
                e.correlationId(),
                e.quality(),
                e.payload());
    }

    public static byte[] bytes(CanonicalEvent event) {
        return CODEC.encodeBytes(event);
    }

    public static CanonicalEvent decode(byte[] bytes) {
        return CODEC.decode(bytes);
    }

    private CanonicalEvent copy(
            CanonicalEvent e, String type, String entityId, Instant occurredAt, Long sequence, ObjectNode payload) {
        return new CanonicalEvent(
                e.eventId(),
                e.schemaVersion(),
                e.portId(),
                e.sourceSystem(),
                e.sourceEventId(),
                sequence,
                e.receptionId(),
                type,
                e.entityType(),
                entityId,
                e.aggregateId(),
                occurredAt,
                e.receivedAt(),
                e.correlationId(),
                e.quality(),
                payload);
    }

    private CanonicalEvent event(
            String type, String entityType, String entityId, Instant occurredAt, Long sequence, ObjectNode payload) {
        long n = counter.incrementAndGet();
        return new CanonicalEvent(
                UUID.randomUUID().toString(),
                CanonicalEvent.SCHEMA_VERSION,
                PORT,
                SOURCE,
                "T-" + n,
                sequence,
                "rec-" + n,
                type,
                entityType,
                entityId,
                entityId,
                occurredAt,
                occurredAt.plus(400, ChronoUnit.MILLIS),
                null,
                Quality.SIMULATED,
                payload);
    }

    private static ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode();
    }
}
