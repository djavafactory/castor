package com.djavafactory.castor.core.search.api;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.djavafactory.castor.core.search.application.SearchPlaces;
import com.djavafactory.castor.core.search.domain.PlaceHit;

/** Place search over the port's own data. There is no outside geocoder and nothing that is typed is stored. */
@Path("/api/v1/ports/{portCode}/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    private final SearchPlaces searchPlaces;

    public SearchResource(SearchPlaces searchPlaces) {
        this.searchPlaces = searchPlaces;
    }

    /** Up to 12 places by name or code. The answer is never cached: it follows what the user typed. */
    @GET
    @RolesAllowed({"VIEWER", "OPERATOR", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response search(
            @PathParam("portCode") String portCode,
            @QueryParam("q") String q,
            @QueryParam("layoutId") UUID layoutId,
            @Context SecurityContext security) {
        boolean editor =
                security.isUserInRole("EDITOR") || security.isUserInRole("PUBLISHER") || security.isUserInRole("ADMIN");
        List<PlaceHit> hits = searchPlaces.execute(portCode, q, layoutId, editor);
        return Response.ok(hits).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
