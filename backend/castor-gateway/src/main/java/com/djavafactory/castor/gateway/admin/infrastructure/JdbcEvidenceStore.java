package com.djavafactory.castor.gateway.admin.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.gateway.admin.domain.EvidenceStore;
import com.djavafactory.castor.gateway.shared.domain.Json;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcEvidenceStore implements EvidenceStore {

    private static final String SOURCES = "select s.code, s.enabled, s.require_signature, s.quality_default, "
            + "s.freshness_sla::text, "
            + "(select count(*) from ingest.api_key k where k.source_id = s.id and k.revoked_at is null), "
            + "(select count(*) from ingest.inbox i where i.port_id = s.port_id and i.source = s.code "
            + "and i.replay_of is null and i.received_at > ?), "
            + "(select coalesce(sum(d.count), 0) from ingest.intake_duplicate d where d.port_id = s.port_id "
            + "and d.source = s.code and d.minute >= date_trunc('minute', ?::timestamptz)), "
            + "(select count(*) from ingest.quarantine q join ingest.inbox i on i.reception_id = q.reception_id "
            + "where i.port_id = s.port_id and i.source = s.code and i.replay_of is null and q.created_at > ?), "
            + "(select max(i.received_at) from ingest.inbox i where i.port_id = s.port_id and i.source = s.code "
            + "and i.replay_of is null) "
            + "from ingest.integration_source s where s.port_id = ? order by s.code";

    private static final String ENTRY_COLUMNS = "q.id, q.reception_id, i.source, i.source_event_id, q.reason_code, "
            + "q.detail, i.received_at, q.created_at, i.replay_of, "
            + "(select count(*) from ingest.inbox r where r.replay_of = coalesce(i.replay_of, i.reception_id)), "
            + "(select r.status from ingest.inbox r where r.replay_of = coalesce(i.replay_of, i.reception_id) "
            + "order by r.reception_id desc limit 1), q.subject_type, q.subject_id";
    private static final String ENTRY_FROM =
            " from ingest.quarantine q join ingest.inbox i on i.reception_id = q.reception_id where i.port_id = ?";

    private static final String RAW = "select raw::text from ingest.inbox where reception_id = ?";
    private static final String ATTEMPTS = "select r.reception_id, r.received_at, r.status, q.reason_code, q.detail, "
            + "(select count(*) from ingest.outbox o where o.reception_id = r.reception_id), "
            + "(select count(*) from ingest.outbox o where o.reception_id = r.reception_id and o.published_at is not null) "
            + "from ingest.inbox r left join ingest.quarantine q on q.reception_id = r.reception_id "
            + "where r.replay_of = ? order by r.reception_id";

    private static final String LOCK = "select i.reception_id, i.replay_of, i.source, i.source_event_id, "
            + "i.raw is not null, i.raw::text from ingest.inbox i join ingest.quarantine q on q.reception_id = i.reception_id "
            + "where i.reception_id = ? and i.port_id = ? for update of i";
    private static final String PENDING = "select reception_id from ingest.inbox where replay_of = ? "
            + "and status = 'RECEIVED' order by reception_id limit 1";
    private static final String INSERT_REPLAY = "insert into ingest.inbox (reception_id, port_id, source, "
            + "source_event_id, received_at, raw, status, replay_of) values (?, ?, ?, ?, ?, ?::jsonb, 'RECEIVED', ?)";

    private final Jdbc jdbc;

    public JdbcEvidenceStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SourceCounters> sources(String portId, Instant since) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(SOURCES)) {
                Timestamp from = Timestamp.from(since);
                ps.setTimestamp(1, from);
                ps.setTimestamp(2, from);
                ps.setTimestamp(3, from);
                ps.setString(4, portId);
                try (var rs = ps.executeQuery()) {
                    List<SourceCounters> sources = new ArrayList<>();
                    while (rs.next()) {
                        Timestamp last = rs.getTimestamp(10);
                        sources.add(new SourceCounters(
                                rs.getString(1),
                                rs.getBoolean(2),
                                rs.getBoolean(3),
                                rs.getString(4),
                                rs.getInt(6),
                                rs.getLong(7),
                                rs.getLong(8),
                                rs.getLong(9),
                                last == null ? null : last.toInstant(),
                                slaEntries(rs.getString(5))));
                    }
                    return sources;
                }
            }
        });
    }

    private static List<String> slaEntries(String json) {
        try {
            JsonNode sla = Json.parse(json);
            List<String> durations = new ArrayList<>();
            sla.elements().forEachRemaining(v -> durations.add(v.asText()));
            return durations;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("freshness_sla is not JSON", e);
        }
    }

    @Override
    public List<QuarantineEntry> quarantine(String portId, QuarantineFilter filter) {
        StringBuilder sql = new StringBuilder("select " + ENTRY_COLUMNS + ENTRY_FROM);
        List<Object> args = new ArrayList<>();
        args.add(portId);
        if (filter.source() != null) {
            sql.append(" and i.source = ?");
            args.add(filter.source());
        }
        if (filter.reason() != null) {
            sql.append(" and q.reason_code = ?");
            args.add(filter.reason());
        }
        if (filter.before() != null) {
            sql.append(" and q.id < ?");
            args.add(filter.before());
        }
        sql.append(" order by q.id desc limit ?");
        args.add(filter.limit());
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    ps.setObject(i + 1, args.get(i));
                }
                try (var rs = ps.executeQuery()) {
                    List<QuarantineEntry> entries = new ArrayList<>();
                    while (rs.next()) {
                        entries.add(entry(rs));
                    }
                    return entries;
                }
            }
        });
    }

    @Override
    public Optional<QuarantineView> quarantined(String portId, UUID receptionId) {
        return jdbc.read(c -> {
            QuarantineEntry entry;
            try (var ps = c.prepareStatement("select " + ENTRY_COLUMNS + ENTRY_FROM + " and q.reception_id = ?")) {
                ps.setString(1, portId);
                ps.setObject(2, receptionId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    entry = entry(rs);
                }
            }
            UUID original = entry.replayOf() == null ? receptionId : entry.replayOf();
            return Optional.of(new QuarantineView(entry, raw(c, receptionId), attempts(c, original)));
        });
    }

    private static JsonNode raw(Connection c, UUID receptionId) throws SQLException {
        try (var ps = c.prepareStatement(RAW)) {
            ps.setObject(1, receptionId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? parse(rs.getString(1)) : null;
            }
        }
    }

    private static List<ReplayAttempt> attempts(Connection c, UUID original) throws SQLException {
        try (var ps = c.prepareStatement(ATTEMPTS)) {
            ps.setObject(1, original);
            try (var rs = ps.executeQuery()) {
                List<ReplayAttempt> attempts = new ArrayList<>();
                while (rs.next()) {
                    attempts.add(new ReplayAttempt(
                            rs.getObject(1, UUID.class),
                            rs.getTimestamp(2).toInstant(),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getInt(6),
                            rs.getInt(7)));
                }
                return attempts;
            }
        }
    }

    @Override
    public Replayed replay(String portId, UUID receptionId, UUID newReceptionId, Instant now) {
        return jdbc.inTransaction(c -> {
            UUID original;
            String source;
            String sourceEventId;
            String raw;
            try (var ps = c.prepareStatement(LOCK)) {
                ps.setObject(1, receptionId);
                ps.setString(2, portId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new Replayed.NotQuarantined();
                    }
                    UUID replayOf = rs.getObject(2, UUID.class);
                    original = replayOf == null ? receptionId : replayOf;
                    source = rs.getString(3);
                    sourceEventId = rs.getString(4);
                    if (!rs.getBoolean(5)) {
                        return new Replayed.RawExpired();
                    }
                    raw = rs.getString(6);
                }
            }
            try (var ps = c.prepareStatement(PENDING)) {
                ps.setObject(1, original);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Replayed.Pending(rs.getObject(1, UUID.class));
                    }
                }
            }
            try (var ps = c.prepareStatement(INSERT_REPLAY)) {
                ps.setObject(1, newReceptionId);
                ps.setString(2, portId);
                ps.setString(3, source);
                ps.setString(4, sourceEventId);
                ps.setTimestamp(5, Timestamp.from(now));
                ps.setString(6, raw);
                ps.setObject(7, original);
                ps.executeUpdate();
            }
            return new Replayed.Created(newReceptionId, original);
        });
    }

    private static QuarantineEntry entry(ResultSet rs) throws SQLException {
        return new QuarantineEntry(
                rs.getLong(1),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8).toInstant(),
                rs.getObject(9, UUID.class),
                rs.getInt(10),
                rs.getString(11),
                rs.getString(12),
                rs.getString(13));
    }

    private static JsonNode parse(String json) {
        try {
            return json == null ? null : Json.parse(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The inbox holds JSON that cannot be read", e);
        }
    }
}
