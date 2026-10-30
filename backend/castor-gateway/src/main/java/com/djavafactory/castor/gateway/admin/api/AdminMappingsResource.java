package com.djavafactory.castor.gateway.admin.api;

import java.time.Instant;
import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;

import com.djavafactory.castor.gateway.admin.application.ManageMappings;
import com.djavafactory.castor.gateway.admin.domain.MappingAdminStore.MappingEntry;
import com.djavafactory.castor.gateway.admin.domain.MappingAdminStore.UnresolvedId;

/** The identity mappings of a port and the source ids still waiting for one. Service token only. */
@Path("/internal/v1/ports/{portId}/mappings")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class AdminMappingsResource {

    /**
     * @param actor the user core acts for, stored as {@code created_by}
     * @param effectiveFrom default: the epoch, so every event of the source id maps
     * @param effectiveTo default: none (the mapping stays open)
     */
    public record CreateRequest(
            String sourceSystem,
            String sourceEntityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            Instant effectiveTo,
            String actor) {}

    /** @param effectiveTo default: now */
    public record CloseRequest(Instant effectiveTo) {}

    public record MappingsResponse(List<MappingEntry> items) {}

    public record UnresolvedResponse(List<UnresolvedId> items) {}

    private final ManageMappings mappings;

    public AdminMappingsResource(ManageMappings mappings) {
        this.mappings = mappings;
    }

    @GET
    public MappingsResponse list(
            @PathParam("portId") String portId,
            @QueryParam("source") String source,
            @QueryParam("entityType") String entityType,
            @QueryParam("sourceId") String sourceId,
            @QueryParam("state") String state,
            @QueryParam("limit") Integer limit) {
        return new MappingsResponse(mappings.list(portId, source, entityType, sourceId, state, limit));
    }

    /** Source ids the quarantine could not map, with how many records wait on each. */
    @GET
    @Path("unresolved")
    public UnresolvedResponse unresolved(@PathParam("portId") String portId) {
        return new UnresolvedResponse(mappings.unresolved(portId));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@PathParam("portId") String portId, CreateRequest request) {
        CreateRequest body = request == null ? new CreateRequest(null, null, null, null, null, null, null) : request;
        MappingEntry created = mappings.create(
                portId,
                new ManageMappings.NewMapping(
                        body.sourceSystem(),
                        body.sourceEntityType(),
                        body.sourceId(),
                        body.canonicalId(),
                        body.effectiveFrom(),
                        body.effectiveTo()),
                body.actor());
        return Response.status(201).entity(created).build();
    }

    @POST
    @Path("{id}/close")
    @Consumes(MediaType.APPLICATION_JSON)
    public MappingEntry close(@PathParam("portId") String portId, @PathParam("id") long id, CloseRequest request) {
        return mappings.close(portId, id, request == null ? null : request.effectiveTo());
    }
}
