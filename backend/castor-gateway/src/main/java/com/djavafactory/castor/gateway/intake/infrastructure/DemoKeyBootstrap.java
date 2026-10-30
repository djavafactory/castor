package com.djavafactory.castor.gateway.intake.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.intake.domain.ApiKeys;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.shared.application.StartupStep;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

/**
 * Local, dev and test only: creates the port's sources and gives each the API key of GATEWAY_DEMO_KEY (written hashed,
 * origin 'env'). The key of an earlier value is revoked, so changing .env and restarting leaves exactly one demo key.
 * {@code canonical} is enabled; the system sources stay disabled until their adapters exist (INGEST-03), so they answer
 * 403. Safe to run again.
 */
@ApplicationScoped
public class DemoKeyBootstrap implements StartupStep {

    private static final Logger LOG = Logger.getLogger(DemoKeyBootstrap.class);

    private static final String UPSERT_SOURCE =
            "insert into ingest.integration_source (port_id, code, enabled, quality_default, created_at, updated_at) "
                    + "values (?, ?, ?, 'SIMULATED', ?, ?) on conflict (port_id, code) do nothing";
    private static final String SOURCE_ID = "select id from ingest.integration_source where port_id = ? and code = ?";
    private static final String REVOKE_OTHERS = "update ingest.api_key set revoked_at = ? "
            + "where source_id = ? and origin = 'env' and revoked_at is null and key_hash <> ?";
    private static final String UPSERT_KEY =
            "insert into ingest.api_key (source_id, prefix, key_hash, origin, created_at) values (?, ?, ?, 'env', ?) "
                    + "on conflict (source_id, key_hash) do update set revoked_at = null";

    private final CastorConfig castor;
    private final Jdbc jdbc;
    private final InstantSource clock;

    public DemoKeyBootstrap(CastorConfig castor, Jdbc jdbc, InstantSource clock) {
        this.castor = castor;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run() {
        if (!CastorConfig.KNOWN_ENVIRONMENTS.contains(castor.env())) {
            return;
        }
        Optional<String> key = castor.intake().demoKey();
        Instant now = clock.instant();
        String port = castor.portCode();
        jdbc.inTransaction(c -> {
            for (Source source : Source.values()) {
                try (var ps = c.prepareStatement(UPSERT_SOURCE)) {
                    ps.setString(1, port);
                    ps.setString(2, source.code());
                    ps.setBoolean(3, source == Source.CANONICAL);
                    ps.setTimestamp(4, Timestamp.from(now));
                    ps.setTimestamp(5, Timestamp.from(now));
                    ps.executeUpdate();
                }
                if (key.isEmpty()) {
                    continue;
                }
                long sourceId = sourceId(c, port, source);
                byte[] hash = ApiKeys.hash(key.get());
                try (var ps = c.prepareStatement(REVOKE_OTHERS)) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setLong(2, sourceId);
                    ps.setBytes(3, hash);
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement(UPSERT_KEY)) {
                    ps.setLong(1, sourceId);
                    ps.setString(2, ApiKeys.prefix(key.get()));
                    ps.setBytes(3, hash);
                    ps.setTimestamp(4, Timestamp.from(now));
                    ps.executeUpdate();
                }
            }
            return null;
        });
        LOG.infof(
                "Sources of port %s ready (%d); demo key %s",
                port, Source.values().length, key.isPresent() ? "set from GATEWAY_DEMO_KEY" : "not set");
    }

    private static long sourceId(Connection c, String port, Source source) throws SQLException {
        try (var ps = c.prepareStatement(SOURCE_ID)) {
            ps.setString(1, port);
            ps.setString(2, source.code());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
