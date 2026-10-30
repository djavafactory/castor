package com.djavafactory.castor.gateway.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.normalize.domain.SourceAdapter;

/**
 * Adapters for sources that have none yet (INGEST-03 adds the real ones), to exercise the SPI: one record that makes
 * several events, a rejection with an adapter's own reason code, and an adapter that fails.
 */
public final class TestAdapters {

    private TestAdapters() {}

    /** Source edic: {@code {"count": 3}} makes three events, each with a made-up eventId the normalizer must replace. */
    @ApplicationScoped
    public static class Edic implements SourceAdapter {

        @Override
        public String source() {
            return "edic";
        }

        @Override
        public List<CanonicalEvent> normalize(InboxRecord record) {
            int count = record.raw().path("count").asInt(1);
            List<CanonicalEvent> events = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                events.add(event(record, "gate-" + i, "00000000-0000-0000-0000-00000000000" + i));
            }
            return events;
        }
    }

    /** Source sensors: {@code {"reject": true}} is refused with CUSTOM_REASON, {@code {"boom": true}} throws. */
    @ApplicationScoped
    public static class Sensors implements SourceAdapter {

        @Override
        public String source() {
            return "sensors";
        }

        @Override
        public List<CanonicalEvent> normalize(InboxRecord record) throws Rejection {
            JsonNode raw = record.raw();
            if (raw.path("reject").asBoolean()) {
                throw new Rejection("CUSTOM_REASON", "The adapter does not like this record");
            }
            if (raw.path("boom").asBoolean()) {
                throw new IllegalStateException("adapter bug with a secret detail: hunter2");
            }
            return List.of(event(record, "tank-1", "00000000-0000-0000-0000-000000000009"));
        }
    }

    static CanonicalEvent event(InboxRecord record, String aggregateId, String eventId) {
        return new CanonicalEvent(
                eventId,
                1,
                record.portId(),
                "TEST",
                record.sourceEventId(),
                null,
                record.receptionId().toString(),
                "equipment.status.changed",
                "EQUIPMENT",
                aggregateId,
                aggregateId,
                Instant.parse("2026-10-29T08:20:00Z"),
                record.receivedAt(),
                null,
                Quality.SIMULATED,
                JsonNodeFactory.instance.objectNode().put("status", "IDLE"));
    }
}
