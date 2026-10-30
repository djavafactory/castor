package com.djavafactory.castor.core.operations.api;

import java.time.Instant;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import com.djavafactory.castor.core.operations.application.OperationsPorts;
import com.djavafactory.castor.core.operations.application.StreamTickets;

/** Hands out the single-use ticket that opens the WebSocket of a port. */
@Path("/api/v1/ports/{portId}/stream/ticket")
@Produces(MediaType.APPLICATION_JSON)
public class StreamTicketResource {

    public record TicketResponse(String ticket, Instant expiresAt) {}

    private final OperationsPorts ports;
    private final StreamTickets tickets;

    public StreamTicketResource(OperationsPorts ports, StreamTickets tickets) {
        this.ports = ports;
        this.tickets = tickets;
    }

    @POST
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public TicketResponse issue(@PathParam("portId") String portId, @Context SecurityContext security) {
        var ticket =
                tickets.issue(ports.portId(portId), security.getUserPrincipal().getName());
        return new TicketResponse(ticket.ticket(), ticket.expiresAt());
    }
}
