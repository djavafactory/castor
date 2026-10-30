package com.djavafactory.castor.core.operations.api;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;

import com.djavafactory.castor.core.operations.application.StreamTickets;

/**
 * The handshake of the stream: the ticket in the query is redeemed (once) and must belong to the port in the path.
 * Anything else is refused with HTTP 401 before a WebSocket exists. The user of the ticket is kept for the session.
 */
@ApplicationScoped
public class StreamTicketCheck implements HttpUpgradeCheck {

    private final StreamTickets tickets;

    StreamTicketCheck(StreamTickets tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean appliesTo(String endpointId) {
        return StreamEndpoint.class.getName().equals(endpointId);
    }

    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext context) {
        return tickets.redeemOnWorker(
                        context.pathParam("portId"), context.httpRequest().getParam("ticket"))
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
}
