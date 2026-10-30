package com.djavafactory.castor.core.operations.api;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import com.djavafactory.castor.core.operations.application.DeadLetterReplays;
import com.djavafactory.castor.core.operations.application.OperationsPorts;
import com.djavafactory.castor.core.operations.application.ReviewItems;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;

/**
 * What the Data quality tab needs, for one port ({@code portId} is the port code): the review items the projections
 * opened and the events the projector gave up on. OPERATOR and ADMIN look and resolve items; only ADMIN replays a dead
 * letter. Resolving records the human decision only: data is changed by {@code inventory.adjusted} events, never here.
 * Every action is audited.
 */
@Path("/api/v1/ports/{portId}")
@Produces(MediaType.APPLICATION_JSON)
public class DataQualityResource {

    /** @param note why the item needs no further action, or what was done about it; required */
    public record ResolveRequest(String note) {}

    private final OperationsPorts ports;
    private final ReviewItems items;
    private final DeadLetterReplays replays;

    public DataQualityResource(OperationsPorts ports, ReviewItems items, DeadLetterReplays replays) {
        this.ports = ports;
        this.items = items;
        this.replays = replays;
    }

    /** {@code status} is OPEN (default) or RESOLVED; {@code kind} narrows to one kind of item. Newest first. */
    @GET
    @Path("reconciliation-items")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public List<ReconciliationItemDto> items(
            @PathParam("portId") String portId, @QueryParam("status") String status, @QueryParam("kind") String kind) {
        return items.list(
                ports.portId(portId),
                parse(ReconciliationStatus.class, "status", status),
                parse(ReconciliationKind.class, "kind", kind));
    }

    @POST
    @Path("reconciliation-items/{id}/resolve")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public ReconciliationItemDto resolve(
            @PathParam("portId") String portId, @PathParam("id") long id, ResolveRequest request) {
        return items.resolve(portId.toLowerCase(), ports.portId(portId), id, request == null ? null : request.note());
    }

    /** First attempts of the port, newest first, each with the state of its replays. */
    @GET
    @Path("dead-letters")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public List<DeadLetterDto> deadLetters(@PathParam("portId") String portId) {
        return replays.list(ports.portId(portId));
    }

    /** One dead letter with every replay attempt linked to it. */
    @GET
    @Path("dead-letters/{id}")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public DeadLetterDto deadLetter(@PathParam("portId") String portId, @PathParam("id") long id) {
        return replays.get(ports.portId(portId), id);
    }

    @POST
    @Path("dead-letters/{id}/replay")
    @RolesAllowed({"ADMIN"})
    public DeadLetterReplayDto replay(@PathParam("portId") String portId, @PathParam("id") long id) {
        return replays.replay(portId.toLowerCase(), ports.portId(portId), id);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(name + " must be one of " + List.of(type.getEnumConstants()));
        }
    }
}
