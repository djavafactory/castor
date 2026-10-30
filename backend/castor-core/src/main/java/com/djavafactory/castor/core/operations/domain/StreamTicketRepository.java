package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.Optional;

/** Single-use tickets that let a browser open the WebSocket. Only the hash of a ticket is stored. */
public interface StreamTicketRepository {

    /** Stores the ticket and removes the ones that expired before {@code now}. */
    void issue(String tokenHash, long portId, String username, Instant expiresAt, Instant now);

    /** Deletes the ticket and returns its user when it is of the port and not expired at {@code now}; else empty. */
    Optional<String> redeem(String tokenHash, long portId, Instant now);
}
