package it.replica8.castor.core.support;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** One endpoint per role group of the access table, so the tests can check 401, 403 and 200 for each. */
@Path("/api/v1/test-roles")
public class RoleProbeResource {

    @GET
    @Path("/read")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public String read() {
        return "ok";
    }

    @GET
    @Path("/edit")
    @RolesAllowed({"EDITOR", "ADMIN"})
    public String edit() {
        return "ok";
    }

    @GET
    @Path("/publish")
    @RolesAllowed({"PUBLISHER", "ADMIN"})
    public String publish() {
        return "ok";
    }

    @GET
    @Path("/admin")
    @RolesAllowed("ADMIN")
    public String admin() {
        return "ok";
    }
}
