package com.djavafactory.castor.core.operations.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.core.operations.domain.CanonicalEventRecord;
import com.djavafactory.castor.core.operations.domain.EventLogRepository;

/** ops.canonical_event (immutable) and ops.processed_event. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcEventLogRepository implements EventLogRepository {

    private final OpsSql sql;

    JdbcEventLogRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public boolean append(CanonicalEventRecord e) {
        return sql.update(
                        "insert into ops.canonical_event (event_id, port_id, type, aggregate_id, occurred_at, "
                                + "received_at, quality, body) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                                + "on conflict (event_id) do nothing",
                        e.eventId(),
                        e.portId(),
                        e.type(),
                        e.aggregateId(),
                        e.occurredAt(),
                        e.receivedAt(),
                        e.quality(),
                        e.body())
                == 1;
    }

    @Override
    public Optional<CanonicalEventRecord> find(UUID eventId) {
        return sql.queryOne(
                "select event_id, port_id, type, aggregate_id, occurred_at, received_at, quality, body "
                        + "from ops.canonical_event where event_id = ?",
                r -> new CanonicalEventRecord(
                        r.uuid("event_id"),
                        r.lng("port_id"),
                        r.text("type"),
                        r.text("aggregate_id"),
                        r.instant("occurred_at"),
                        r.instant("received_at"),
                        Quality.valueOf(r.text("quality")),
                        r.jsonObject("body")),
                eventId);
    }

    @Override
    public boolean markProcessed(long portId, String consumer, UUID eventId) {
        return sql.update(
                        "insert into ops.processed_event (port_id, consumer, event_id) values (?, ?, ?) "
                                + "on conflict (consumer, event_id) do nothing",
                        portId,
                        consumer,
                        eventId)
                == 1;
    }

    @Override
    public boolean isProcessed(String consumer, UUID eventId) {
        return !sql.query(
                        "select 1 as one from ops.processed_event where consumer = ? and event_id = ?",
                        r -> r.integer("one"),
                        consumer,
                        eventId)
                .isEmpty();
    }
}
