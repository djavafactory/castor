package com.djavafactory.castor.core.integration.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.core.integration.application.ChangeIntegrations;
import com.djavafactory.castor.core.integration.application.ReadIntegrations;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;

/**
 * What the Integrations page needs, for one port. Browsers reach integration data through here only (rule R7): the
 * gateway's own API is internal. OPERATOR and ADMIN may look, only ADMIN may act, and every action is audited.
 *
 * <p>Lists and details are the gateway's JSON as it is: {@code sources} (counters of the last 24 hours and freshness),
 * {@code quarantine} (newest first, keyset {@code before}/{@code nextBefore}), a quarantined record with its payload
 * (keys and secrets masked) and its replay attempts, API keys (prefix only) and identity mappings.
 */
@Path("/api/v1/ports/{portId}/integrations")
@Produces(MediaType.APPLICATION_JSON)
public class IntegrationResource {

    /** @param effectiveFrom default: the epoch (every event of the source id maps). @param effectiveTo default: none */
    public record MappingRequest(
            String sourceSystem,
            String sourceEntityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    /** @param effectiveTo default: now */
    public record CloseMappingRequest(Instant effectiveTo) {}

    private final ReadIntegrations read;
    private final ChangeIntegrations change;

    public IntegrationResource(ReadIntegrations read, ChangeIntegrations change) {
        this.read = read;
        this.change = change;
    }

    // --- view: OPERATOR, ADMIN ---

    @GET
    @Path("sources")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public JsonNode sources(@PathParam("portId") String portId) {
        return read.sources(portId);
    }

    @GET
    @Path("sources/{source}/keys")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public JsonNode keys(@PathParam("portId") String portId, @PathParam("source") String source) {
        return read.keys(portId, source);
    }

    @GET
    @Path("quarantine")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public JsonNode quarantine(
            @PathParam("portId") String portId,
            @QueryParam("source") String source,
            @QueryParam("reason") String reason,
            @QueryParam("before") Long before,
            @QueryParam("limit") Integer limit) {
        return read.quarantine(portId, source, reason, before, limit);
    }

    @GET
    @Path("quarantine/{receptionId}")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public JsonNode quarantined(@PathParam("portId") String portId, @PathParam("receptionId") String receptionId) {
        return read.quarantined(portId, id(receptionId));
    }

    @GET
    @Path("mappings")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public JsonNode mappings(
            @PathParam("portId") String portId,
            @QueryParam("source") String source,
            @QueryParam("entityType") String entityType,
            @QueryParam("sourceId") String sourceId,
            @QueryParam("state") String state,
            @QueryParam("limit") Integer limit) {
        return read.mappings(portId, source, entityType, sourceId, state, limit);
    }

    /** UNKNOWN_MAPPING and AMBIGUOUS_MAPPING counts per source id. */
    @GET
    @Path("mappings/unresolved")
    @RolesAllowed({"OPERATOR", "ADMIN"})
    public JsonNode unresolved(@PathParam("portId") String portId) {
        return read.unresolved(portId);
    }

    // --- act: ADMIN ---

    /** Puts a quarantined record through the pipeline again; 201 {receptionId, replayOf}. The original is not changed. */
    @POST
    @Path("quarantine/{receptionId}/replay")
    @RolesAllowed("ADMIN")
    public Response replay(@PathParam("portId") String portId, @PathParam("receptionId") String receptionId) {
        return Response.status(201)
                .entity(change.replay(portId, id(receptionId)))
                .build();
    }

    /** 201 with the new key in {@code key}: it is shown this once. */
    @POST
    @Path("sources/{source}/keys")
    @RolesAllowed("ADMIN")
    public Response createKey(@PathParam("portId") String portId, @PathParam("source") String source) {
        return secret(change.createKey(portId, source));
    }

    /** Revokes the key and issues a new one: 201 with the new key in {@code key}, shown once. */
    @POST
    @Path("sources/{source}/keys/{keyId}/rotate")
    @RolesAllowed("ADMIN")
    public Response rotateKey(
            @PathParam("portId") String portId, @PathParam("source") String source, @PathParam("keyId") long keyId) {
        return secret(change.rotateKey(portId, source, keyId));
    }

    @DELETE
    @Path("sources/{source}/keys/{keyId}")
    @RolesAllowed("ADMIN")
    public JsonNode revokeKey(
            @PathParam("portId") String portId, @PathParam("source") String source, @PathParam("keyId") long keyId) {
        return change.revokeKey(portId, source, keyId);
    }

    @POST
    @Path("mappings")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response createMapping(@PathParam("portId") String portId, MappingRequest request) {
        if (request == null) {
            throw new InvalidRequestException("A mapping is required");
        }
        return Response.status(201)
                .entity(change.createMapping(
                        portId,
                        new ChangeIntegrations.NewMapping(
                                request.sourceSystem(),
                                request.sourceEntityType(),
                                request.sourceId(),
                                request.canonicalId(),
                                request.effectiveFrom(),
                                request.effectiveTo())))
                .build();
    }

    /** Ends the mapping (default: now). It stays in the list, closed. */
    @POST
    @Path("mappings/{id}/close")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public JsonNode closeMapping(
            @PathParam("portId") String portId, @PathParam("id") long id, CloseMappingRequest request) {
        return change.closeMapping(portId, id, request == null ? null : request.effectiveTo());
    }

    private static Response secret(JsonNode created) {
        return Response.status(201)
                .header("Cache-Control", "no-store")
                .entity(created)
                .build();
    }

    private static UUID id(String receptionId) {
        try {
            return UUID.fromString(receptionId);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("The reception id must be a UUID");
        }
    }
}
