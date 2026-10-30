package com.djavafactory.castor.gateway.admin.api;

import java.time.Instant;
import java.util.List;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.smallrye.common.annotation.Blocking;

import com.djavafactory.castor.gateway.admin.application.ManageApiKeys;
import com.djavafactory.castor.gateway.admin.application.ReadEvidence;
import com.djavafactory.castor.gateway.admin.domain.ApiKeyStore.ApiKeySummary;
import com.djavafactory.castor.gateway.admin.domain.EvidenceStore.SourceStatus;

/** The sources of a port with their counters, and their API keys. Service token only. */
@Path("/internal/v1/ports/{portId}/sources")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class AdminSourcesResource {

    /**
     * A key as the operator sees it. {@code key} is set only in the answer that creates the key and is never readable
     * again.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KeyResponse(long id, String prefix, String origin, Instant createdAt, Instant revokedAt, String key) {

        static KeyResponse of(ApiKeySummary k) {
            return new KeyResponse(k.id(), k.prefix(), k.origin(), k.createdAt(), k.revokedAt(), null);
        }

        static KeyResponse of(ManageApiKeys.IssuedKey issued) {
            ApiKeySummary k = issued.summary();
            return new KeyResponse(k.id(), k.prefix(), k.origin(), k.createdAt(), k.revokedAt(), issued.key());
        }
    }

    public record SourcesResponse(List<SourceStatus> items) {}

    public record KeysResponse(List<KeyResponse> items) {}

    private final ReadEvidence evidence;
    private final ManageApiKeys keys;

    public AdminSourcesResource(ReadEvidence evidence, ManageApiKeys keys) {
        this.evidence = evidence;
        this.keys = keys;
    }

    @GET
    public SourcesResponse sources(@PathParam("portId") String portId) {
        return new SourcesResponse(evidence.sources(portId));
    }

    @GET
    @Path("{source}/keys")
    public KeysResponse keys(@PathParam("portId") String portId, @PathParam("source") String source) {
        return new KeysResponse(
                keys.list(portId, source).stream().map(KeyResponse::of).toList());
    }

    @POST
    @Path("{source}/keys")
    public Response create(@PathParam("portId") String portId, @PathParam("source") String source) {
        return Response.status(201)
                .entity(KeyResponse.of(keys.create(portId, source)))
                .build();
    }

    @POST
    @Path("{source}/keys/{keyId}/rotate")
    public Response rotate(
            @PathParam("portId") String portId, @PathParam("source") String source, @PathParam("keyId") long keyId) {
        return Response.status(201)
                .entity(KeyResponse.of(keys.rotate(portId, source, keyId)))
                .build();
    }

    @DELETE
    @Path("{source}/keys/{keyId}")
    public KeyResponse revoke(
            @PathParam("portId") String portId, @PathParam("source") String source, @PathParam("keyId") long keyId) {
        return KeyResponse.of(keys.revoke(portId, source, keyId));
    }
}
