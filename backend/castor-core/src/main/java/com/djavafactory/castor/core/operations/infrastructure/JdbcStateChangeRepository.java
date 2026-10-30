package com.djavafactory.castor.core.operations.infrastructure;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;

/** ops.state_change and ops.stream_cursor. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcStateChangeRepository implements StateChangeRepository {

    private static final String SELECT = "select port_id, sequence, entity_type, entity_id, entity_version, "
            + "occurred_at, body, published_at from ops.state_change ";

    private final OpsSql sql;

    JdbcStateChangeRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public StateChange append(
            long portId,
            String entityType,
            String entityId,
            long entityVersion,
            Instant occurredAt,
            Map<String, Object> body) {
        // One statement creates the cursor or moves it on. It locks the port's row until the caller's transaction
        // ends, so writers queue up, and a rollback undoes the increment together with the change: no gaps.
        long sequence = sql.queryOne(
                        "insert into ops.stream_cursor (port_id, last_sequence) values (?, 1) "
                                + "on conflict (port_id) do update set last_sequence = ops.stream_cursor.last_sequence + 1 "
                                + "returning last_sequence",
                        r -> r.lng("last_sequence"),
                        portId)
                .orElseThrow();
        sql.update(
                "insert into ops.state_change (port_id, sequence, entity_type, entity_id, entity_version, occurred_at, "
                        + "body) values (?, ?, ?, ?, ?, ?, ?::jsonb)",
                portId,
                sequence,
                entityType,
                entityId,
                entityVersion,
                occurredAt,
                body);
        return new StateChange(portId, sequence, entityType, entityId, entityVersion, occurredAt, body, null);
    }

    @Override
    public List<StateChange> after(long portId, long sequence, int limit) {
        return sql.query(
                SELECT + "where port_id = ? and sequence > ? order by sequence limit ?",
                JdbcStateChangeRepository::map,
                portId,
                sequence,
                limit);
    }

    @Override
    public List<StateChange> unpublished(long portId, int limit) {
        return sql.query(
                SELECT + "where port_id = ? and published_at is null order by sequence limit ?",
                JdbcStateChangeRepository::map,
                portId,
                limit);
    }

    @Override
    public void markPublished(long portId, long upToSequence, Instant at) {
        sql.update(
                "update ops.state_change set published_at = ? "
                        + "where port_id = ? and sequence <= ? and published_at is null",
                at,
                portId,
                upToSequence);
    }

    @Override
    public Map<Long, String> portsWithUnpublished() {
        Map<Long, String> ports = new LinkedHashMap<>();
        sql.query(
                        "select p.id, p.code from ref.port p where exists (select 1 from ops.state_change c "
                                + "where c.port_id = p.id and c.published_at is null) order by p.id",
                        r -> Map.entry(r.lng("id"), r.text("code")))
                .forEach(e -> ports.put(e.getKey(), e.getValue()));
        return ports;
    }

    @Override
    public boolean tryLockPublishing(long portId) {
        // the first key names the lock (the digits of "STATE" and 03), the second the port
        return sql.queryOne(
                        "select pg_try_advisory_xact_lock(5300003, ?::int) as locked", r -> r.bool("locked"), portId)
                .orElse(false);
    }

    @Override
    public long lastSequence(long portId) {
        return sql.queryOne(
                        "select last_sequence from ops.stream_cursor where port_id = ?",
                        r -> r.lng("last_sequence"),
                        portId)
                .orElse(0L);
    }

    private static StateChange map(OpsSql.Row r) {
        return new StateChange(
                r.lng("port_id"),
                r.lng("sequence"),
                r.text("entity_type"),
                r.text("entity_id"),
                r.lng("entity_version"),
                r.instant("occurred_at"),
                r.jsonObject("body"),
                r.instant("published_at"));
    }
}
