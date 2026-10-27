package com.djavafactory.castor.core.layout.api;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
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

import com.djavafactory.castor.core.layout.application.DiscardDraft;
import com.djavafactory.castor.core.layout.application.GetLayout;
import com.djavafactory.castor.core.layout.application.LayoutValidation;
import com.djavafactory.castor.core.layout.application.ListLayoutVersions;
import com.djavafactory.castor.core.layout.domain.IssueLevel;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.shared.api.CachedJson;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Layout versions by id: the newest version of each port, any version as GeoJSON, and a single asset. */
@Path("/api/v1/layouts")
@Produces(MediaType.APPLICATION_JSON)
public class LayoutResource {

    public record LayoutVersionResponse(UUID id, String portCode, int versionNo, LayoutStatus status) {}

    /** One failed rule: code and wording of the catalogue, the assets it is about (the first is the one to select). */
    public record IssueResponse(String code, IssueLevel level, String message, List<String> assetIds) {}

    public record ValidationResponse(int errors, int warnings, List<IssueResponse> issues) {}

    private final ListLayoutVersions listLayoutVersions;
    private final GetLayout getLayout;
    private final DiscardDraft discardDraft;
    private final LayoutValidation validation;
    private final LayoutFeatureMapper mapper;
    private final CachedJson cache;

    public LayoutResource(
            ListLayoutVersions listLayoutVersions,
            GetLayout getLayout,
            DiscardDraft discardDraft,
            LayoutValidation validation,
            LayoutFeatureMapper mapper,
            CachedJson cache) {
        this.listLayoutVersions = listLayoutVersions;
        this.getLayout = getLayout;
        this.discardDraft = discardDraft;
        this.validation = validation;
        this.mapper = mapper;
        this.cache = cache;
    }

    @GET
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public List<LayoutVersionResponse> list() {
        return listLayoutVersions.execute().stream()
                .map(v -> new LayoutVersionResponse(v.id(), v.portCode(), v.versionNo(), v.status()))
                .toList();
    }

    /** Any version of the layout. Drafts and discarded versions are visible to editors and above only. */
    @GET
    @Path("{layoutId}")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response get(
            @PathParam("layoutId") UUID layoutId,
            @HeaderParam("If-None-Match") String ifNoneMatch,
            @Context SecurityContext security) {
        var result = getLayout.byId(layoutId);
        requireVisible(result.view().version(), security);
        return cache.tagged(ifNoneMatch, result.etag(), () -> mapper.layout(result.view()));
    }

    /** Discards a draft. The rows stay for the audit trail; the next entry to Design starts a new draft. */
    @DELETE
    @Path("{layoutId}")
    @RolesAllowed({"PUBLISHER", "ADMIN"})
    public Response discard(@PathParam("layoutId") UUID layoutId) {
        discardDraft.execute(layoutId);
        return Response.noContent().build();
    }

    /**
     * Every error and warning of a version, for the Issues list. Read-only: nothing is stored and no audit row is
     * written, so it may be asked as often as needed.
     */
    @POST
    @Path("{layoutId}/validate")
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public ValidationResponse validate(@PathParam("layoutId") UUID layoutId) {
        var report = validation.layout(layoutId);
        return new ValidationResponse(
                report.errors().size(),
                report.warnings().size(),
                report.issues().stream()
                        .map(i -> new IssueResponse(i.code(), i.level(), i.message(), i.assetIds()))
                        .toList());
    }

    /** One feature of one version; its ETag is the row version of the revision. */
    @GET
    @Path("{layoutId}/assets/{assetId}")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response asset(
            @PathParam("layoutId") UUID layoutId,
            @PathParam("assetId") UUID assetId,
            @HeaderParam("If-None-Match") String ifNoneMatch,
            @Context SecurityContext security) {
        var result = getLayout.asset(layoutId, assetId);
        requireVisible(result.view().version(), security);
        var asset = result.view().assets().get(0);
        return cache.tagged(
                ifNoneMatch,
                result.etag(),
                () -> mapper.feature(asset, result.view().measures().get(asset.id())));
    }

    static void requireVisible(LayoutVersion version, SecurityContext security) {
        boolean open = version.status() == LayoutStatus.PUBLISHED || version.status() == LayoutStatus.SUPERSEDED;
        boolean editor =
                security.isUserInRole("EDITOR") || security.isUserInRole("PUBLISHER") || security.isUserInRole("ADMIN");
        if (!open && !editor) {
            throw new ResourceNotFoundException("No layout version " + version.id());
        }
    }
}
