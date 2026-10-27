package com.djavafactory.castor.core.support;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Stands in for the GeoJSON import endpoint, which is allowed a larger body than the rest of the API. */
@Path("/api/v1/layouts/{id}/import")
public class ImportTestResource {

    @POST
    @Consumes("application/geo+json")
    @Produces(MediaType.TEXT_PLAIN)
    public String importGeoJson(@PathParam("id") String id, String body) {
        return String.valueOf(body.length());
    }
}
