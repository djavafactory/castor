package it.replica8.castor.core.port.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import it.replica8.castor.core.port.application.GetCatalogue;
import it.replica8.castor.core.shared.api.CachedJson;

@Path("/api/v1/catalogue")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogueResource {

    private final GetCatalogue getCatalogue;
    private final CachedJson cache;

    public CatalogueResource(GetCatalogue getCatalogue, CachedJson cache) {
        this.getCatalogue = getCatalogue;
        this.cache = cache;
    }

    @GET
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response catalogue(@HeaderParam("If-None-Match") String ifNoneMatch) {
        return cache.of(ifNoneMatch, getCatalogue.execute());
    }
}
