package com.djavafactory.castor.gateway.housekeeping.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.housekeeping.domain.RetentionStore;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcRetentionStore implements RetentionStore {

    private static final String DELETE_OUTBOX =
            "delete from ingest.outbox where published_at is not null and published_at < ?";
    private static final String EMPTY_NORMALIZED_RAW = "update ingest.inbox set raw = null "
            + "where status = 'NORMALIZED' and raw is not null and normalized_at < ?";
    private static final String EMPTY_QUARANTINED_RAW = "update ingest.inbox set raw = null "
            + "where raw is not null and reception_id in (select reception_id from ingest.quarantine where created_at < ?)";
    private static final String DELETE_QUARANTINE = "delete from ingest.quarantine where created_at < ?";

    private final Jdbc jdbc;

    public JdbcRetentionStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Purged purge(Instant publishedBefore, Instant normalizedBefore, Instant quarantinedBefore) {
        return jdbc.inTransaction(c -> {
            int outbox = run(c, DELETE_OUTBOX, publishedBefore);
            int raw = run(c, EMPTY_NORMALIZED_RAW, normalizedBefore) + run(c, EMPTY_QUARANTINED_RAW, quarantinedBefore);
            int quarantine = run(c, DELETE_QUARANTINE, quarantinedBefore);
            return new Purged(outbox, raw, quarantine);
        });
    }

    private static int run(Connection c, String sql, Instant before) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(before));
            return ps.executeUpdate();
        }
    }
}
