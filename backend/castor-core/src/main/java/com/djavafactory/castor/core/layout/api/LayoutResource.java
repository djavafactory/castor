package com.djavafactory.castor.core.layout.api;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.djavafactory.castor.core.layout.application.ListLayoutVersions;

@Path("/api/v1/layouts")
@Produces(MediaType.APPLICATION_JSON)
public class LayoutResource {

    public record LayoutVersionResponse(UUID id, UUID layoutId, int number) {}

    private final ListLayoutVersions listLayoutVersions;

    public LayoutResource(ListLayoutVersions listLayoutVersions) {
        this.listLayoutVersions = listLayoutVersions;
    }

    @GET
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public List<LayoutVersionResponse> list() {
        return listLayoutVersions.execute().stream()
                .map(v -> new LayoutVersionResponse(v.id(), v.layoutId(), v.number()))
                .toList();
    }
}
