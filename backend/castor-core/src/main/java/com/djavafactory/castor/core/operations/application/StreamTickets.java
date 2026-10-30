package com.djavafactory.castor.core.operations.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.operations.domain.StreamTicketRepository;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * Single-use tickets for the WebSocket: a browser cannot send Basic auth on one, so it asks for a ticket with its
 * credentials and opens the stream with that. A ticket is bound to the user and the port, lives 30 s and works once.
 */
@ApplicationScoped
public class StreamTickets {

    public record Ticket(String ticket, Instant expiresAt) {}

    private final StreamTicketRepository tickets;
    private final InstantSource clock;
    private final CastorConfig castor;
    private final SecureRandom random = new SecureRandom();

    StreamTickets(StreamTicketRepository tickets, InstantSource clock, CastorConfig castor) {
        this.tickets = tickets;
        this.clock = clock;
        this.castor = castor;
    }

    @Transactional
    public Ticket issue(long portId, String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(castor.state().stream().ticketTtl());
        tickets.issue(hash(ticket), portId, username, expiresAt, now);
        return new Ticket(ticket, expiresAt);
    }

    /** The user the ticket was issued to; empty when it is unknown, used, expired or of another port. */
    @Transactional
    public Optional<String> redeem(long portId, String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.length() > 128) {
            return Optional.empty();
        }
        return tickets.redeem(hash(ticket), portId, clock.instant());
    }

    static String hash(String ticket) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
