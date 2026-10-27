package com.djavafactory.castor.core.support;

import java.sql.SQLException;
import java.util.ArrayList;

import javax.sql.DataSource;

/**
 * Data isolation between test classes: empties every table in the application schemas and keeps the structure.
 * The container is fresh per test JVM and Flyway migrates once, so this is all a class needs for a clean start.
 */
public final class TestDatabase {

    private static final String TABLES = "SELECT format('%I.%I', schemaname, tablename) FROM pg_tables "
            + "WHERE schemaname IN ('ref','layout','audit','security') AND tablename <> 'flyway_schema_history' "
            + "ORDER BY 1";

    private TestDatabase() {}

    public static void reset(DataSource dataSource) {
        try (var c = dataSource.getConnection();
                var s = c.createStatement()) {
            var tables = new ArrayList<String>();
            try (var rs = s.executeQuery(TABLES)) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            if (!tables.isEmpty()) {
                s.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset the test database", e);
        }
    }
}
