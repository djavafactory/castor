package com.djavafactory.castor.core.shared.infrastructure;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.shared.application.DataSeedStep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reset and seed against a real PostGIS (Dev Services / Testcontainers). */
@QuarkusTest
@TestProfile(DataResetIT.ResetOnStart.class)
class DataResetIT {

    public static class ResetOnStart implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("castor.env", "test", "castor.data.reset-on-start", "true");
        }
    }

    @ApplicationScoped
    public static class CountingSeed implements DataSeedStep {
        static final AtomicInteger RUNS = new AtomicInteger();

        @Override
        public int seed() {
            RUNS.incrementAndGet();
            return 3;
        }
    }

    @Inject
    DataSource dataSource;

    @Inject
    DataResetService reset;

    @Inject
    CastorConfig config;

    private boolean probeExists() throws SQLException {
        try (var c = dataSource.getConnection();
                var rs = c.createStatement().executeQuery("select to_regclass('layout.reset_probe') is not null")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private long opsRows() throws SQLException {
        try (var c = dataSource.getConnection();
                var rs = c.createStatement()
                        .executeQuery(
                                "select (select count(*) from ops.stream_cursor) + (select count(*) from ops.state_change)"
                                        + " + (select count(*) from ops.vessel_call) + (select count(*) from ops.canonical_event)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void startUpSeededOnce() {
        assertTrue(config.data().resetOnStart());
        assertTrue(CountingSeed.RUNS.get() >= 1, "the seed step ran at start-up");
    }

    @Test
    void resetDropsEditsAndSeedsAgainWithTheSameResult() throws SQLException {
        try (var c = dataSource.getConnection()) {
            c.createStatement().execute("create table layout.reset_probe(id int)");
            c.createStatement()
                    .execute(
                            "insert into ops.stream_cursor (port_id, last_sequence) select id, 5 from ref.port limit 1");
        }
        assertEquals(1, opsRows(), "an operational row exists before the reset");
        assertTrue(probeExists());

        int before = CountingSeed.RUNS.get();
        reset.reset();
        assertFalse(probeExists(), "edits made after start are gone");
        assertEquals(0, opsRows(), "operational data is wiped with the rest");
        assertEquals(before + 1, CountingSeed.RUNS.get());

        reset.reset();
        assertFalse(probeExists());
        assertEquals(before + 2, CountingSeed.RUNS.get(), "a second reset gives the same result");
    }
}
