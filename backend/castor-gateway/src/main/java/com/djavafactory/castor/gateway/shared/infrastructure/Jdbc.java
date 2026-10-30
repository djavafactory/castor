package com.djavafactory.castor.gateway.shared.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * One connection per unit of work. {@link #inTransaction} commits when the work returns and rolls back when it throws,
 * so "committed before answering" is a property of the call, not of a framework annotation.
 */
@ApplicationScoped
public class Jdbc {

    /** Work against one connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;

    public Jdbc(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T inTransaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollback(c, e);
                throw e instanceof RuntimeException r ? r : new DatabaseException((SQLException) e);
            }
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    /** A statement or two outside a transaction (autocommit). */
    public <T> T read(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    private static void rollback(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException suppressed) {
            cause.addSuppressed(suppressed);
        }
    }

    /** The database failed; the caller cannot do anything about it. */
    public static class DatabaseException extends RuntimeException {
        public DatabaseException(SQLException cause) {
            super("Database error: " + cause.getMessage(), cause);
        }
    }
}
