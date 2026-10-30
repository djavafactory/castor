package com.djavafactory.castor.core.layout.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.djavafactory.castor.core.layout.application.ListAssetTypes;
import com.djavafactory.castor.core.shared.api.CachedJson;

@Path("/api/v1/asset-types")
@Produces(MediaType.APPLICATION_JSON)
public class AssetTypeResource {

    private final ListAssetTypes listAssetTypes;
    private final CachedJson cache;

    public AssetTypeResource(ListAssetTypes listAssetTypes, CachedJson cache) {
        this.listAssetTypes = listAssetTypes;
        this.cache = cache;
    }

    @GET
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response list(@HeaderParam("If-None-Match") String ifNoneMatch) {
        return cache.of(ifNoneMatch, listAssetTypes.execute());
    }
}
