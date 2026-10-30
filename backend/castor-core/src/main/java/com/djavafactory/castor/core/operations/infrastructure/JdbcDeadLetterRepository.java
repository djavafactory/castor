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

    @Override
    public List<Entry> listOriginals(long portId, int limit) {
        return entries(portId, null, limit);
    }

    @Override
    public Optional<Entry> original(long portId, long id) {
        return entries(portId, id, 1).stream().findFirst();
    }

    private List<Entry> entries(long portId, Long id, int limit) {
        return sql.query(
                "select d.id, d.port_id, d.consumer, d.event_id, d.topic, d.partition, d.\"offset\", d.error, "
                        + "d.attempts, d.body, d.replay_of, d.created_at, "
                        + "(select count(*) from ops.dead_letter r where r.replay_of = d.id) as replays, "
                        + "(select r.id from ops.dead_letter r where r.replay_of = d.id "
                        + "order by r.created_at desc, r.id desc limit 1) as last_replay_id, "
                        + "(select r.error from ops.dead_letter r where r.replay_of = d.id "
                        + "order by r.created_at desc, r.id desc limit 1) as last_replay_error "
                        + "from ops.dead_letter d where d.port_id = ? and d.replay_of is null "
                        + "and (?::bigint is null or d.id = ?::bigint) order by d.created_at desc, d.id desc limit ?",
                r -> new Entry(
                        map(r),
                        r.instant("created_at"),
                        r.integer("replays"),
                        r.longOrNull("last_replay_id"),
                        r.text("last_replay_error")),
                portId,
                id,
                id,
                limit);
    }

    @Override
    public List<DeadLetter> replaysOf(long id) {
        return sql.query(SELECT + "where replay_of = ? order by created_at, id", JdbcDeadLetterRepository::map, id);
    }
}
