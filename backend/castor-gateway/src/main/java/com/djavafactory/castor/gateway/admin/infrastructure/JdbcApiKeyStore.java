package com.djavafactory.castor.gateway.admin.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.admin.domain.AdminException;
import com.djavafactory.castor.gateway.admin.domain.ApiKeyStore;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcApiKeyStore implements ApiKeyStore {

    private static final String SOURCE_ID = "select id from ingest.integration_source where port_id = ? and code = ?";
    private static final String COLUMNS = "id, prefix, origin, created_at, revoked_at";
    private static final String KEYS =
            "select " + COLUMNS + " from ingest.api_key where source_id = ? order by id desc";
    private static final String INSERT = "insert into ingest.api_key (source_id, prefix, key_hash, origin, created_at) "
            + "values (?, ?, ?, 'issued', ?) returning " + COLUMNS;
    private static final String LOCK =
            "select " + COLUMNS + " from ingest.api_key where id = ? and source_id = ? for update";
    private static final String REVOKE = "update ingest.api_key set revoked_at = ? where id = ? returning " + COLUMNS;

    private final Jdbc jdbc;

    public JdbcApiKeyStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApiKeySummary> keys(String portId, Source source) {
        return jdbc.read(c -> {
            long sourceId = sourceId(c, portId, source);
            try (var ps = c.prepareStatement(KEYS)) {
                ps.setLong(1, sourceId);
                try (var rs = ps.executeQuery()) {
                    List<ApiKeySummary> keys = new ArrayList<>();
                    while (rs.next()) {
                        keys.add(summary(rs));
                    }
                    return keys;
                }
            }
        });
    }

    @Override
    public ApiKeySummary add(String portId, Source source, String prefix, byte[] hash, Instant now) {
        return jdbc.inTransaction(c -> insert(c, sourceId(c, portId, source), prefix, hash, now));
    }

    @Override
    public ApiKeySummary rotate(String portId, Source source, long keyId, String prefix, byte[] hash, Instant now) {
        return jdbc.inTransaction(c -> {
            long sourceId = sourceId(c, portId, source);
            requireActive(c, sourceId, keyId);
            revoke(c, keyId, now);
            return insert(c, sourceId, prefix, hash, now);
        });
    }

    @Override
    public ApiKeySummary revoke(String portId, Source source, long keyId, Instant now) {
        return jdbc.inTransaction(c -> {
            requireActive(c, sourceId(c, portId, source), keyId);
            return revoke(c, keyId, now);
        });
    }

    private static long sourceId(Connection c, String portId, Source source) throws SQLException {
        try (var ps = c.prepareStatement(SOURCE_ID)) {
            ps.setString(1, portId);
            ps.setString(2, source.code());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AdminException.notFound(
                            "SOURCE_NOT_FOUND", "Port " + portId + " has no source " + source.code());
                }
                return rs.getLong(1);
            }
        }
    }

    private static ApiKeySummary insert(Connection c, long sourceId, String prefix, byte[] hash, Instant now)
            throws SQLException {
        try (var ps = c.prepareStatement(INSERT)) {
            ps.setLong(1, sourceId);
            ps.setString(2, prefix);
            ps.setBytes(3, hash);
            ps.setTimestamp(4, Timestamp.from(now));
            try (var rs = ps.executeQuery()) {
                rs.next();
                return summary(rs);
            }
        }
    }

    /** Locks the key row and refuses a key that is not there, is revoked already, or is the demo key. */
    private static void requireActive(Connection c, long sourceId, long keyId) throws SQLException {
        try (var ps = c.prepareStatement(LOCK)) {
            ps.setLong(1, keyId);
            ps.setLong(2, sourceId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw AdminException.notFound("KEY_NOT_FOUND", "No such key for this source");
                }
                ApiKeySummary key = summary(rs);
                if (key.revokedAt() != null) {
                    throw AdminException.conflict("KEY_REVOKED", "The key is revoked already");
                }
                if ("env".equals(key.origin())) {
                    throw AdminException.conflict(
                            "KEY_FROM_ENVIRONMENT", "This key comes from GATEWAY_DEMO_KEY: change it there");
                }
            }
        }
    }

    private static ApiKeySummary revoke(Connection c, long keyId, Instant now) throws SQLException {
        try (var ps = c.prepareStatement(REVOKE)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setLong(2, keyId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return summary(rs);
            }
        }
    }

    private static ApiKeySummary summary(ResultSet rs) throws SQLException {
        Timestamp revoked = rs.getTimestamp(5);
        return new ApiKeySummary(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getTimestamp(4).toInstant(),
                revoked == null ? null : revoked.toInstant());
    }
}
