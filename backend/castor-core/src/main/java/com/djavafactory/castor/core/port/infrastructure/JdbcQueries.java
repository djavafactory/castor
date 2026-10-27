package com.djavafactory.castor.core.port.infrastructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/** Tiny read helper: one connection per call, rows mapped by a lambda. */
final class JdbcQueries {

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final DataSource dataSource;

    JdbcQueries(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        try (Connection c = dataSource.getConnection()) {
            return query(c, sql, binder, mapper);
        } catch (SQLException e) {
            throw new IllegalStateException("Reading reference data failed", e);
        }
    }

    static <T> List<T> query(Connection c, String sql, Binder binder, RowMapper<T> mapper) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        }
    }

    /** The first column of the single row the statement returns. */
    String single(String sql, Binder binder) {
        return query(sql, binder, rs -> rs.getString(1)).get(0);
    }
}
