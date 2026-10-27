package com.djavafactory.castor.core.layout.api;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.djavafactory.castor.core.layout.application.GetLayout;
import com.djavafactory.castor.core.layout.application.ListLayoutVersions;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.shared.api.CachedJson;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Layout versions by id: the newest version of each port, any version as GeoJSON, and a single asset. */
@Path("/api/v1/layouts")
@Produces(MediaType.APPLICATION_JSON)
public class LayoutResource {

    public record LayoutVersionResponse(UUID id, String portCode, int versionNo, LayoutStatus status) {}

    private final ListLayoutVersions listLayoutVersions;
    private final GetLayout getLayout;
    private final LayoutFeatureMapper mapper;
    private final CachedJson cache;

    public LayoutResource(
            ListLayoutVersions listLayoutVersions, GetLayout getLayout, LayoutFeatureMapper mapper, CachedJson cache) {
        this.listLayoutVersions = listLayoutVersions;
        this.getLayout = getLayout;
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
