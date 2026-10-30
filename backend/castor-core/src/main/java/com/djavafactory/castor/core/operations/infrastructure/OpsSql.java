package com.djavafactory.castor.core.operations.infrastructure;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plain SQL for the ops repositories. The connection comes from the pool and joins the transaction of the caller, so a
 * repository call never commits on its own: the writer decides where the transaction ends.
 */
@ApplicationScoped
class OpsSql {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final DataSource dataSource;
    private final ObjectMapper json;

    OpsSql(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    <T> List<T> query(String sql, Function<Row, T> mapper, Object... params) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = prepare(c, sql, params);
                ResultSet rs = ps.executeQuery()) {
            List<T> rows = new ArrayList<>();
            Row row = new Row(rs, json);
            while (rs.next()) {
                rows.add(mapper.apply(row));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Reading operational state failed", e);
        }
    }

    <T> Optional<T> queryOne(String sql, Function<Row, T> mapper, Object... params) {
        return query(sql, mapper, params).stream().findFirst();
    }

    /** Runs the statement; the number of rows it changed. */
    int update(String sql, Object... params) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Writing operational state failed", e);
        }
    }

    private PreparedStatement prepare(Connection c, String sql, Object[] params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, bind(params[i]));
            }
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
        return ps;
    }

    private Object bind(Object value) {
        if (value instanceof Instant instant) {
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Map<?, ?> map) {
            try {
                return json.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Not a JSON object", e);
            }
        }
        return value;
    }

    /** A result row read by column name; every getter returns null for SQL NULL, never 0. */
    static final class Row {

        private final ResultSet rs;
        private final ObjectMapper json;

        private Row(ResultSet rs, ObjectMapper json) {
            this.rs = rs;
            this.json = json;
        }

        String text(String column) {
            return get(() -> rs.getString(column));
        }

        UUID uuid(String column) {
            return get(() -> rs.getObject(column, UUID.class));
        }

        Instant instant(String column) {
            OffsetDateTime at = get(() -> rs.getObject(column, OffsetDateTime.class));
            return at == null ? null : at.toInstant();
        }

        BigDecimal decimal(String column) {
            return get(() -> rs.getBigDecimal(column));
        }

        boolean bool(String column) {
            return get(() -> rs.getBoolean(column));
        }

        Long longOrNull(String column) {
            return get(() -> {
                long value = rs.getLong(column);
                return rs.wasNull() ? null : value;
            });
        }

        long lng(String column) {
            return get(() -> rs.getLong(column));
        }

        int integer(String column) {
            return get(() -> rs.getInt(column));
        }

        Double doubleOrNull(String column) {
            return get(() -> {
                double value = rs.getDouble(column);
                return rs.wasNull() ? null : value;
            });
        }

        <E extends Enum<E>> E enumOf(Class<E> type, String column) {
            String name = text(column);
            return name == null ? null : Enum.valueOf(type, name);
        }

        Map<String, Object> jsonObject(String column) {
            String text = text(column);
            if (text == null) {
                return null;
            }
            try {
                return json.readValue(text, JSON_OBJECT);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Column " + column + " does not hold a JSON object", e);
            }
        }

        private <T> T get(SqlGetter<T> getter) {
            try {
                return getter.get();
            } catch (SQLException e) {
                throw new IllegalStateException("Reading a column failed", e);
            }
        }
    }

    @FunctionalInterface
    private interface SqlGetter<T> {
        T get() throws SQLException;
    }
}
