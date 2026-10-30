package com.djavafactory.castor.gateway.intake.infrastructure;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.intake.domain.InboxRepository;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcInboxRepository implements InboxRepository {

    /** Postgres class 22: the data cannot be stored (for example an escaped NUL in a JSON string). */
    private static final String DATA_EXCEPTION_CLASS = "22";

    private static final String INSERT =
            "insert into ingest.inbox (reception_id, port_id, source, source_event_id, received_at, raw, status) "
                    + "values (?, ?, ?, ?, ?, ?::jsonb, 'RECEIVED') "
                    + "on conflict (port_id, source, source_event_id) do nothing returning reception_id";
    private static final String EXISTING =
            "select reception_id from ingest.inbox where port_id = ? and source = ? and source_event_id = ?";

    private final Jdbc jdbc;

    public JdbcInboxRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Stored> store(String portId, Source source, List<NewRecord> records) {
        return jdbc.inTransaction(c -> {
            List<Stored> stored = new ArrayList<>(records.size());
            // One record needs no savepoint: if it cannot be stored nothing else is in the transaction to protect
            boolean protect = records.size() > 1;
            try (var insert = c.prepareStatement(INSERT);
                    var existing = c.prepareStatement(EXISTING)) {
                for (NewRecord record : records) {
                    Savepoint savepoint = protect ? c.setSavepoint() : null;
                    try {
                        insert.setObject(1, record.receptionId());
                        insert.setString(2, portId);
                        insert.setString(3, source.code());
                        insert.setString(4, record.sourceEventId());
                        insert.setTimestamp(5, Timestamp.from(record.receivedAt()));
                        insert.setString(6, record.rawJson());
                        try (var rs = insert.executeQuery()) {
                            if (rs.next()) {
                                stored.add(new Stored.Accepted(rs.getObject(1, UUID.class)));
                            } else {
                                stored.add(new Stored.Duplicate(firstOf(existing, portId, source, record)));
                            }
                        }
                        if (savepoint != null) {
                            c.releaseSavepoint(savepoint);
                        }
                    } catch (SQLException e) {
                        if (e.getSQLState() == null || !e.getSQLState().startsWith(DATA_EXCEPTION_CLASS)) {
                            throw e;
                        }
                        if (savepoint != null) {
                            c.rollback(savepoint);
                        }
                        stored.add(new Stored.Unstorable("The record contains data the database cannot store"));
                    }
                }
            }
            return stored;
        });
    }

    private static UUID firstOf(PreparedStatement existing, String portId, Source source, NewRecord record)
            throws SQLException {
        existing.setString(1, portId);
        existing.setString(2, source.code());
        existing.setString(3, record.sourceEventId());
        try (var rs = existing.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("Duplicate record vanished: " + record.sourceEventId());
            }
            return rs.getObject(1, UUID.class);
        }
    }
}
