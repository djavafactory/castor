package com.djavafactory.castor.gateway.intake.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.intake.domain.IntegrationSource;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.intake.domain.SourceRegistry;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcSourceRegistry implements SourceRegistry {

    private static final String FIND = "select id, port_id, code, enabled, require_signature, hmac_secret_ref "
            + "from ingest.integration_source where port_id = ? and code = ?";
    private static final String HAS_KEY =
            "select 1 from ingest.api_key where source_id = ? and key_hash = ? and revoked_at is null";
    private static final String ADD_KEY =
            "insert into ingest.api_key (source_id, prefix, key_hash, created_at) values (?, ?, ?, ?)";

    private final Jdbc jdbc;

    public JdbcSourceRegistry(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IntegrationSource> find(String portId, Source source) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(FIND)) {
                ps.setString(1, portId);
                ps.setString(2, source.code());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new IntegrationSource(
                            rs.getLong("id"),
                            rs.getString("port_id"),
                            source,
                            rs.getBoolean("enabled"),
                            rs.getBoolean("require_signature"),
                            rs.getString("hmac_secret_ref")));
                }
            }
        });
    }

    @Override
    public boolean hasActiveKey(long sourceId, byte[] keyHash) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(HAS_KEY)) {
                ps.setLong(1, sourceId);
                ps.setBytes(2, keyHash);
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public void addKey(long sourceId, String prefix, byte[] keyHash, Instant createdAt) {
        jdbc.inTransaction(c -> {
            try (var ps = c.prepareStatement(ADD_KEY)) {
                ps.setLong(1, sourceId);
                ps.setString(2, prefix);
                ps.setBytes(3, keyHash);
                ps.setTimestamp(4, Timestamp.from(createdAt));
                return ps.executeUpdate();
            }
        });
    }
}
