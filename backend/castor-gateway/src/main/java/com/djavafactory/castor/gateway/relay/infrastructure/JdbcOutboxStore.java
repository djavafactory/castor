package com.djavafactory.castor.gateway.relay.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.relay.domain.OutboxMessage;
import com.djavafactory.castor.gateway.relay.domain.OutboxStore;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;

@ApplicationScoped
public class JdbcOutboxStore implements OutboxStore {

    /** Arbitrary constant: the advisory lock that makes one relay at a time run, across every gateway process. */
    public static final long RELAY_LOCK = 0x4341_5354_4F52_0001L;

    private static final String LOCK = "select pg_try_advisory_xact_lock(?)";
    private static final String TAKE = "select id, event_id::text, aggregate_id, event_type, payload::text "
            + "from ingest.outbox where published_at is null order by id limit ?";
    private static final String MARK = "update ingest.outbox set published_at = ? where id = any(?)";

    private final Jdbc jdbc;
    private final InstantSource clock;

    public JdbcOutboxStore(Jdbc jdbc, InstantSource clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public int relay(int batchSize, Function<List<OutboxMessage>, List<Long>> publish) {
        return jdbc.inTransaction(c -> {
            if (!lock(c)) {
                return 0;
            }
            List<OutboxMessage> messages = take(c, batchSize);
            if (messages.isEmpty()) {
                return 0;
            }
            List<Long> published = publish.apply(messages);
            if (published.isEmpty()) {
                return 0;
            }
            return mark(c, published);
        });
    }

    private static boolean lock(Connection c) throws SQLException {
        try (var ps = c.prepareStatement(LOCK)) {
            ps.setLong(1, RELAY_LOCK);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static List<OutboxMessage> take(Connection c, int batchSize) throws SQLException {
        List<OutboxMessage> messages = new ArrayList<>();
        try (var ps = c.prepareStatement(TAKE)) {
            ps.setInt(1, batchSize);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(new OutboxMessage(
                            rs.getLong(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5).getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        return messages;
    }

    private int mark(Connection c, List<Long> ids) throws SQLException {
        Array array = c.createArrayOf("bigint", ids.toArray());
        try (var ps = c.prepareStatement(MARK)) {
            ps.setTimestamp(1, Timestamp.from(clock.instant()));
            ps.setArray(2, array);
            return ps.executeUpdate();
        } finally {
            array.free();
        }
    }
}
