package com.djavafactory.castor.core.operations.api;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import com.djavafactory.castor.core.operations.application.OperationsPorts;
import com.djavafactory.castor.core.operations.application.StreamTickets;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * The handshake of the stream: the ticket in the query is redeemed (once) and must belong to the port in the path.
 * Anything else is refused with HTTP 401 before a WebSocket exists. The user of the ticket is kept for the session.
 */
@ApplicationScoped
public class StreamTicketCheck implements HttpUpgradeCheck {

    private final OperationsPorts ports;
    private final StreamTickets tickets;

    StreamTicketCheck(OperationsPorts ports, StreamTickets tickets) {
        this.ports = ports;
        this.tickets = tickets;
    }

    @Override
    public boolean appliesTo(String endpointId) {
        return StreamEndpoint.class.getName().equals(endpointId);
    }

    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext context) {
        String ticket = context.httpRequest().getParam("ticket");
        String code = context.pathParam("portId");
        // Redeeming reads the database: not on the event loop
        return Uni.createFrom()
                .item(() -> redeem(code, ticket))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem()
                .transformToUni(grant -> {
                    if (grant == null) {
                        return CheckResult.rejectUpgrade(401);
                    }
                    context.userData().put(StreamEndpoint.USERNAME, grant.username());
                    context.userData().put(StreamEndpoint.PORT_ID, grant.portId());
                    return CheckResult.permitUpgrade();
                });
    }

    private record Grant(String username, long portId) {}

    private Grant redeem(String code, String ticket) {
        try {
            long portId = ports.portId(code);
            return tickets.redeem(portId, ticket)
                    .map(name -> new Grant(name, portId))
                    .orElse(null);
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }
}
