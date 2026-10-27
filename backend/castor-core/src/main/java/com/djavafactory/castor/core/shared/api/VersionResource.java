package com.djavafactory.castor.core.shared.api;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.djavafactory.castor.core.shared.application.ReadVersion;

/** Public: the login page shows the server version before anyone is signed in. */
@Path("/api/v1/version")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

    public record VersionResponse(String version, String commit, String buildTime, String environment) {}

    private final ReadVersion readVersion;

    public VersionResource(ReadVersion readVersion) {
        this.readVersion = readVersion;
    }

    @GET
    @PermitAll
    public VersionResponse version() {
        var info = readVersion.execute();
        return new VersionResponse(info.version(), info.commit(), info.buildTime(), info.environment());
    }
}
