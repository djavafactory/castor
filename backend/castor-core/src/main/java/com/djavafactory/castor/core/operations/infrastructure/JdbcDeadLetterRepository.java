package com.djavafactory.castor.core.operations.infrastructure;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.DeadLetter;
import com.djavafactory.castor.core.operations.domain.DeadLetterRepository;

/** ops.dead_letter. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcDeadLetterRepository implements DeadLetterRepository {

    private static final String SELECT = "select id, port_id, consumer, event_id, topic, partition, \"offset\", error, "
            + "attempts, body, replay_of from ops.dead_letter ";

    private final OpsSql sql;

    JdbcDeadLetterRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public DeadLetter add(DeadLetter d) {
        long id = sql.queryOne(
                        "insert into ops.dead_letter (port_id, consumer, event_id, topic, partition, \"offset\", error, "
                                + "attempts, body, replay_of) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id",
                        r -> r.lng("id"),
                        d.portId(),
                        d.consumer(),
                        d.eventId(),
                        d.topic(),
                        d.partition(),
                        d.offset(),
                        d.error(),
                        d.attempts(),
                        d.body(),
                        d.replayOf())
                .orElseThrow();
        return new DeadLetter(
                id,
                d.portId(),
                d.consumer(),
                d.eventId(),
                d.topic(),
                d.partition(),
                d.offset(),
                d.error(),
                d.attempts(),
                d.body(),
                d.replayOf());
    }

    @Override
    public Optional<DeadLetter> find(long id) {
        return sql.queryOne(SELECT + "where id = ?", JdbcDeadLetterRepository::map, id);
    }

    @Override
    public List<DeadLetter> findByConsumer(long portId, String consumer, int limit) {
        return sql.query(
                SELECT + "where port_id = ? and consumer = ? order by created_at desc, id desc limit ?",
                JdbcDeadLetterRepository::map,
                portId,
                consumer,
                limit);
    }

    private static DeadLetter map(OpsSql.Row r) {
        return new DeadLetter(
                r.longOrNull("id"),
                r.longOrNull("port_id"),
                r.text("consumer"),
                r.uuid("event_id"),
                r.text("topic"),
                r.integer("partition"),
                r.lng("offset"),
                r.text("error"),
                r.integer("attempts"),
                r.jsonObject("body"),
                r.longOrNull("replay_of"));
    }
}
