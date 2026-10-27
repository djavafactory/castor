package com.djavafactory.castor.core.layout.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import com.djavafactory.castor.core.layout.application.CreateDraft;
import com.djavafactory.castor.core.layout.application.GetLayout;
import com.djavafactory.castor.core.shared.api.CachedJson;

/** The layouts of one port by port code: the published version and the open draft. */
@Path("/api/v1/ports/{portCode}/layouts")
@Produces(MediaType.APPLICATION_JSON)
public class PortLayoutResource {

    /** @param basedOn "published", "blank" or the id of an earlier version */
    public record CreateDraftRequest(String basedOn) {}

    private final GetLayout getLayout;
    private final CreateDraft createDraft;
    private final LayoutFeatureMapper mapper;
    private final CachedJson cache;

    public PortLayoutResource(
            GetLayout getLayout, CreateDraft createDraft, LayoutFeatureMapper mapper, CachedJson cache) {
        this.getLayout = getLayout;
        this.createDraft = createDraft;
        this.mapper = mapper;
        this.cache = cache;
    }

    /** The published version: {layout, features}. ETag = version id and row versions, 304 supported. */
    @GET
    @Path("current")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response current(@PathParam("portCode") String portCode, @HeaderParam("If-None-Match") String ifNoneMatch) {
        var result = getLayout.current(portCode);
        return cache.tagged(ifNoneMatch, result.etag(), () -> mapper.layout(result.view()));
    }

    /** The open draft, or 404. Editors and above only: a draft is not published. */
    @GET
    @Path("draft")
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public Response draft(@PathParam("portCode") String portCode, @HeaderParam("If-None-Match") String ifNoneMatch) {
        var result = getLayout.draft(portCode);
        return cache.tagged(ifNoneMatch, result.etag(), () -> mapper.layout(result.view()));
    }

    /** Starts the draft of the port; 409 with the id of the existing draft when there already is one. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public Response create(
            @PathParam("portCode") String portCode,
            CreateDraftRequest request,
            @Context SecurityContext security,
            @Context UriInfo uri) {
        var draft = createDraft.execute(
                portCode,
                request == null ? null : request.basedOn(),
                security.getUserPrincipal().getName());
        var result = getLayout.byId(draft.id());
        return Response.created(
                        uri.getBaseUriBuilder().path("api/v1/layouts/{id}").build(draft.id()))
                .entity(mapper.layout(result.view()))
                .build();
    }
}
