package com.djavafactory.castor.core.support;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The test database is a fresh PostGIS with migrations applied, and reset() empties it without touching structure. */
@QuarkusTest
class TestDatabaseIT {

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        TestDatabase.reset(dataSource);
    }

    private long count(String sql) throws Exception {
        try (var c = dataSource.getConnection();
                var rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void databaseIsPostgisWithFlywayHistory() throws Exception {
        assertEquals(1, count("SELECT count(*) FROM pg_extension WHERE extname = 'postgis'"));
        assertTrue(count("SELECT count(*) FROM ref.flyway_schema_history WHERE success") >= 1);
    }

    @Test
    void resetEmptiesTablesAndKeepsStructure() throws Exception {
        try (var c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.execute("CREATE TABLE ref.reset_probe (id int PRIMARY KEY)");
            s.execute("INSERT INTO ref.reset_probe VALUES (1)");
            assertEquals(1, count("SELECT count(*) FROM ref.reset_probe"));

            TestDatabase.reset(dataSource);

            assertEquals(0, count("SELECT count(*) FROM ref.reset_probe"));
            s.execute("DROP TABLE ref.reset_probe");
        }
    }
}
