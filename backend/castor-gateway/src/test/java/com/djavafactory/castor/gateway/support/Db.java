package com.djavafactory.castor.gateway.support;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/** Plain SQL for test set-up and assertions, on the test database. */
public final class Db {

    private Db() {}

    /** Empties the tables the pipeline fills. Sources and keys stay: they are the bootstrap, not test data. */
    public static void reset(DataSource dataSource) {
        execute(
                dataSource,
                "truncate ingest.quarantine, ingest.outbox, ingest.inbox, ingest.intake_duplicate restart identity cascade");
    }

    public static int execute(DataSource dataSource, String sql, Object... args) {
        try (var c = dataSource.getConnection();
                var ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long count(DataSource dataSource, String sql, Object... args) {
        return value(dataSource, sql, args);
    }

    @SuppressWarnings("unchecked")
    public static <T> T value(DataSource dataSource, String sql, Object... args) {
        try (var c = dataSource.getConnection();
                var ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? (T) rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first column of every row. */
    @SuppressWarnings("unchecked")
    public static <T> List<T> column(DataSource dataSource, String sql, Object... args) {
        try (var c = dataSource.getConnection();
                var ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (var rs = ps.executeQuery()) {
                List<T> values = new ArrayList<>();
                while (rs.next()) {
                    values.add((T) rs.getObject(1));
                }
                return values;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
