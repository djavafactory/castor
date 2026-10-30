package com.djavafactory.castor.gateway.admin.api;

import java.util.UUID;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;

import com.djavafactory.castor.gateway.admin.application.ReadEvidence;
import com.djavafactory.castor.gateway.admin.application.ReplayQuarantined;
import com.djavafactory.castor.gateway.admin.domain.AdminException;

/** The quarantine: what was refused and why, the stored payload with secrets masked, and replay. Service token only. */
@Path("/internal/v1/ports/{portId}/quarantine")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class AdminQuarantineResource {

    private final ReadEvidence evidence;
    private final ReplayQuarantined replay;

    public AdminQuarantineResource(ReadEvidence evidence, ReplayQuarantined replay) {
        this.evidence = evidence;
        this.replay = replay;
    }

    /** Newest first. {@code before} is the {@code nextBefore} of the previous page. */
    @GET
    public ReadEvidence.QuarantinePage list(
            @PathParam("portId") String portId,
            @QueryParam("source") String source,
            @QueryParam("reason") String reason,
            @QueryParam("before") Long before,
            @QueryParam("limit") Integer limit) {
        return evidence.quarantine(portId, source, reason, before, limit);
    }

    @GET
    @Path("{receptionId}")
    public ReadEvidence.QuarantineDetail detail(
            @PathParam("portId") String portId, @PathParam("receptionId") String receptionId) {
        return evidence.detail(portId, id(receptionId));
    }

    /** Creates a new inbox row linked to the original; 201 {receptionId, replayOf}. */
    @POST
    @Path("{receptionId}/replay")
    public Response replay(@PathParam("portId") String portId, @PathParam("receptionId") String receptionId) {
        return Response.status(201)
                .entity(replay.replay(portId, id(receptionId)))
                .build();
    }

    private static UUID id(String receptionId) {
        try {
            return UUID.fromString(receptionId);
        } catch (IllegalArgumentException e) {
            throw AdminException.invalid("INVALID_RECEPTION_ID", "The reception id must be a UUID");
        }
    }
}
