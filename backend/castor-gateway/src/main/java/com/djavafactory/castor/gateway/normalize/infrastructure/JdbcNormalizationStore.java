package com.djavafactory.castor.gateway.normalize.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.gateway.normalize.domain.Decision;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.NormalizationStore;
import com.djavafactory.castor.gateway.shared.domain.Json;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcNormalizationStore implements NormalizationStore {

    private static final String TAKE =
            "select reception_id, port_id, source, source_event_id, received_at, raw::text from ingest.inbox "
                    + "where status = 'RECEIVED' order by reception_id limit ? for update skip locked";
    private static final String INSERT_OUTBOX =
            "insert into ingest.outbox (reception_id, event_index, event_id, port_id, aggregate_id, event_type, payload, created_at) "
                    + "values (?, ?, ?, ?, ?, ?, ?::jsonb, ?) on conflict (reception_id, event_index) do nothing";
    private static final String INSERT_QUARANTINE =
            "insert into ingest.quarantine (reception_id, reason_code, detail, created_at) values (?, ?, ?, ?) "
                    + "on conflict (reception_id) do nothing";
    private static final String SET_NORMALIZED =
            "update ingest.inbox set status = 'NORMALIZED', normalized_at = ? where reception_id = ?";
    private static final String SET_QUARANTINED =
            "update ingest.inbox set status = 'QUARANTINED' where reception_id = ?";

    private final Jdbc jdbc;
    private final CanonicalEventCodec codec;
    private final InstantSource clock;

    public JdbcNormalizationStore(Jdbc jdbc, CanonicalEventCodec codec, InstantSource clock) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    public Result process(int batchSize, Function<InboxRecord, Decision> decider) {
        return jdbc.inTransaction(c -> {
            Timestamp now = Timestamp.from(clock.instant());
            int normalized = 0;
            int quarantined = 0;
            for (InboxRecord record : take(c, batchSize)) {
                switch (decider.apply(record)) {
                    case Decision.Normalized n -> {
                        writeOutbox(c, record, n.events(), now);
                        update(c, SET_NORMALIZED, now, record.receptionId());
                        normalized++;
                    }
                    case Decision.Quarantined q -> {
                        quarantine(c, record, q, now);
                        update(c, SET_QUARANTINED, null, record.receptionId());
                        quarantined++;
                    }
                }
            }
            return new Result(normalized, quarantined);
        });
    }

    private static List<InboxRecord> take(Connection c, int batchSize) throws SQLException {
        List<InboxRecord> records = new ArrayList<>();
        try (var ps = c.prepareStatement(TAKE)) {
            ps.setInt(1, batchSize);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new InboxRecord(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getTimestamp(5).toInstant(),
                            parse(rs.getString(6))));
                }
            }
        }
        return records;
    }

    private static JsonNode parse(String raw) {
        try {
            return raw == null ? null : Json.parse(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The inbox holds JSON that cannot be read", e);
        }
    }

    private void writeOutbox(Connection c, InboxRecord record, List<CanonicalEvent> events, Timestamp now)
            throws SQLException {
        try (var ps = c.prepareStatement(INSERT_OUTBOX)) {
            for (int i = 0; i < events.size(); i++) {
                CanonicalEvent event = events.get(i);
                ps.setObject(1, record.receptionId());
                ps.setInt(2, i);
                ps.setObject(3, UUID.fromString(event.eventId()));
                ps.setString(4, event.portId());
                ps.setString(5, event.aggregateId());
                ps.setString(6, event.type());
                ps.setString(7, codec.encode(event));
                ps.setTimestamp(8, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void quarantine(Connection c, InboxRecord record, Decision.Quarantined q, Timestamp now)
            throws SQLException {
        try (var ps = c.prepareStatement(INSERT_QUARANTINE)) {
            ps.setObject(1, record.receptionId());
            ps.setString(2, q.reasonCode());
            ps.setString(3, q.detail());
            ps.setTimestamp(4, now);
            ps.executeUpdate();
        }
    }

    private static void update(Connection c, String sql, Timestamp now, UUID receptionId) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            int next = 1;
            if (now != null) {
                ps.setTimestamp(next++, now);
            }
            ps.setObject(next, receptionId);
            ps.executeUpdate();
        }
    }
}
