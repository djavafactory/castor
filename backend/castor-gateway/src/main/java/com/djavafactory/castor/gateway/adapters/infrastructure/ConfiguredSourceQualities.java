package com.djavafactory.castor.gateway.adapters.infrastructure;

import java.time.Duration;
import java.time.InstantSource;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.gateway.adapters.domain.SourceQualities;
import com.djavafactory.castor.gateway.shared.domain.TtlCache;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

/** Reads {@code quality_default} of {@code ingest.integration_source}, once a minute per source. */
@ApplicationScoped
public class ConfiguredSourceQualities implements SourceQualities {

    private static final String QUALITY =
            "select quality_default from ingest.integration_source where port_id = ? and code = ?";

    private record Key(String portId, String source) {}

    private final Jdbc jdbc;
    private final TtlCache<Key, Quality> cache;

    public ConfiguredSourceQualities(Jdbc jdbc, InstantSource clock) {
        this.jdbc = jdbc;
        this.cache = new TtlCache<>(clock, Duration.ofSeconds(60), 100);
    }

    @Override
    public Quality of(String portId, String source) {
        return cache.get(new Key(portId, source), this::load);
    }

    private Quality load(Key key) {
        return jdbc.read(c -> {
            try (var ps = c.prepareStatement(QUALITY)) {
                ps.setString(1, key.portId());
                ps.setString(2, key.source());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "No source " + key.source() + " for port " + key.portId() + " is configured");
                    }
                    return Quality.valueOf(rs.getString(1));
                }
            }
        });
    }

    /** Test hook: a source whose configuration changed is read again at once. */
    public void forget() {
        cache.clear();
    }
}
