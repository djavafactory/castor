package com.djavafactory.castor.core.operations.infrastructure;

import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.StreamTicketRepository;

/** ops.stream_ticket. The delete that redeems a ticket is atomic, so two instances never both accept it. */
@ApplicationScoped
public class JdbcStreamTicketRepository implements StreamTicketRepository {

    private final OpsSql sql;

    JdbcStreamTicketRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public void issue(String tokenHash, long portId, String username, Instant expiresAt, Instant now) {
        sql.update("delete from ops.stream_ticket where expires_at < ?", now);
        sql.update(
                "insert into ops.stream_ticket (token_hash, port_id, username, expires_at) values (?, ?, ?, ?)",
                tokenHash,
                portId,
                username,
                expiresAt);
    }

    @Override
    public Optional<String> redeem(String tokenHash, long portId, Instant now) {
        return sql.queryOne(
                "delete from ops.stream_ticket where token_hash = ? and port_id = ? and expires_at > ? "
                        + "returning username",
                r -> r.text("username"),
                tokenHash,
                portId,
                now);
    }
}
