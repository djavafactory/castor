package com.djavafactory.castor.core.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.VesselCallValueRepository;

/** ops.vessel_call_value. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcVesselCallValueRepository implements VesselCallValueRepository {

    private static final String SELECT =
            "select field, source, value, occurred_at, source_sequence " + "from ops.vessel_call_value ";

    private final OpsSql sql;

    JdbcVesselCallValueRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public void append(long portId, UUID callId, UUID eventId, Value v) {
        sql.update(
                "insert into ops.vessel_call_value (port_id, call_id, field, source, value, occurred_at, "
                        + "source_sequence, event_id) values (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (event_id, field) do nothing",
                portId,
                callId,
                v.field(),
                v.source(),
                v.value(),
                v.occurredAt(),
                v.sourceSequence(),
                eventId);
    }

    @Override
    public List<Value> latestBySource(UUID callId) {
        return sql.query(
                "select distinct on (field, source) field, source, value, occurred_at, source_sequence "
                        + "from ops.vessel_call_value where call_id = ? "
                        + "order by field, source, occurred_at desc, id desc",
                JdbcVesselCallValueRepository::map,
                callId);
    }

    @Override
    public Optional<Value> lastFrom(UUID callId, String source) {
        return sql.queryOne(
                SELECT + "where call_id = ? and source = ? order by occurred_at desc, id desc limit 1",
                JdbcVesselCallValueRepository::map,
                callId,
                source);
    }

    @Override
    public List<Value> history(UUID callId, String field) {
        return sql.query(
                SELECT + "where call_id = ? and field = ? order by occurred_at, id",
                JdbcVesselCallValueRepository::map,
                callId,
                field);
    }

    private static Value map(OpsSql.Row r) {
        return new Value(
                r.text("field"),
                r.text("source"),
                r.text("value"),
                r.instant("occurred_at"),
                r.longOrNull("source_sequence"));
    }
}
