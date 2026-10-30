package com.djavafactory.castor.core.operations.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.jboss.logging.Logger;
import org.postgresql.PGConnection;

import com.djavafactory.castor.core.operations.application.StateChangeSink;
import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;

/**
 * Reads the new ops.state_change rows of every port in sequence order and hands them to the sink, without Kafka. A
 * Postgres NOTIFY (trigger of V18) wakes it at once; without one it looks again after a second, so a missed
 * notification or a broken listening connection costs at most that. One feed runs in each core instance.
 *
 * <p>Rows are read up to the stream cursor of the port, which moves in the transaction that wrote them: everything
 * at or below the cursor is committed, so a row is never skipped and never read twice.
 */
public final class StateChangeFeed implements AutoCloseable {

    static final String CHANNEL = "ops_state_change";
    static final int BATCH = 500;
    static final int FALLBACK_POLL_MILLIS = 1000;

    private static final Logger LOG = Logger.getLogger(StateChangeFeed.class);

    private final DataSource dataSource;
    private final StateChangeRepository changes;
    private final StateChangeSink sink;
    private final Map<Long, Long> delivered = new HashMap<>();
    private volatile boolean running;
    private Thread thread;

    public StateChangeFeed(DataSource dataSource, StateChangeRepository changes, StateChangeSink sink) {
        this.dataSource = dataSource;
        this.changes = changes;
        this.sink = sink;
    }

    /** Starts after what is stored now (nothing earlier is delivered) and keeps going on its own thread. */
    public synchronized void start() {
        if (running) {
            return;
        }
        rebase();
        running = true;
        thread = new Thread(this::run, "castor-state-feed");
        thread.setDaemon(true);
        thread.start();
    }

    /** Forgets what was delivered and starts after the cursors as they are now (after the data was reset). */
    public synchronized void rebase() {
        delivered.clear();
        try {
            cursors().forEach((portId, sequence) -> {
                delivered.put(portId, sequence);
                sink.baseline(portId, sequence);
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the stream cursors", e);
        }
    }

    @Override
    public void close() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        while (running) {
            try (Connection c = dataSource.getConnection()) {
                PGConnection pg = c.unwrap(PGConnection.class);
                try (Statement s = c.createStatement()) {
                    s.execute("LISTEN " + CHANNEL);
                }
                while (running) {
                    drain();
                    // Returns when a notification comes in, else after the polling interval
                    pg.getNotifications(FALLBACK_POLL_MILLIS);
                }
            } catch (SQLException | RuntimeException e) {
                if (running) {
                    LOG.warn("State change feed lost its connection, retrying: " + e.getMessage());
                    LockSupport.parkNanos(FALLBACK_POLL_MILLIS * 1_000_000L);
                }
            }
        }
    }

    /** Delivers everything stored after what was delivered before, port by port. */
    synchronized void drain() throws SQLException {
        for (Map.Entry<Long, Long> cursor : cursors().entrySet()) {
            long portId = cursor.getKey();
            long upTo = cursor.getValue();
            long from = delivered.getOrDefault(portId, 0L);
            if (upTo < from) {
                // the data was reset: the log starts over
                from = 0;
            }
            while (from < upTo) {
                List<StateChange> batch = changes.after(portId, from, BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                for (StateChange change : batch) {
                    sink.accept(change);
                    from = change.sequence();
                    delivered.put(portId, from);
                }
            }
            delivered.put(portId, from);
        }
    }

    private Map<Long, Long> cursors() throws SQLException {
        Map<Long, Long> result = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery("select port_id, last_sequence from ops.stream_cursor order by port_id")) {
            while (rs.next()) {
                result.put(rs.getLong(1), rs.getLong(2));
            }
        }
        return result;
    }
}
