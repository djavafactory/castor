package com.djavafactory.castor.core.identity.api;

import java.util.List;
import java.util.Set;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;

import com.djavafactory.castor.core.identity.application.IdentityUseCases;
import com.djavafactory.castor.core.identity.domain.Role;
import com.djavafactory.castor.core.identity.domain.UserSummary;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class IdentityResource {

    public record MeResponse(String username, List<String> roles, String displayName) {}

    public record DemoUserResponse(String username, String label) {}

    public record ReviewerResponse(String username, String displayName) {}

    private final IdentityUseCases identity;
    private final SecurityIdentity current;

    public IdentityResource(IdentityUseCases identity, SecurityIdentity current) {
        this.identity = identity;
        this.current = current;
    }

    @GET
    @Path("/me")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public MeResponse me() {
        UserSummary user = identity.currentUser(current.getPrincipal().getName());
        if (user == null) {
            // Signed in through another identity provider (a later switch to OIDC): use what the token carries
            return new MeResponse(
                    current.getPrincipal().getName(),
                    sorted(current.getRoles()),
                    current.getPrincipal().getName());
        }
        return new MeResponse(
                user.username(),
                sorted(user.roles().stream().map(Role::name).collect(java.util.stream.Collectors.toSet())),
                user.displayName());
    }

    /** Who may be named as reviewer when publishing: publishers and admins, not the caller. */
    @GET
    @Path("/reviewers")
    @RolesAllowed({"PUBLISHER", "ADMIN"})
    public List<ReviewerResponse> reviewers() {
        return identity.reviewersFor(current.getPrincipal().getName()).stream()
                .map(u -> new ReviewerResponse(u.username(), u.displayName()))
                .toList();
    }

    /** Public: the login page reads it before anyone is signed in. 404 unless demo login is on in local or dev. */
    @GET
    @Path("/auth/demo-users")
    @PermitAll
    public List<DemoUserResponse> demoUsers() {
        var users = identity.demoUsers();
        if (users.isEmpty()) {
            throw new NotFoundException();
        }
        return users.stream()
                .map(u -> new DemoUserResponse(u.username(), u.label()))
                .toList();
    }

    /** Sign out is client side (credentials live in memory only); this only writes the LOGOUT event. */
    @POST
    @Path("/auth/logout")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response logout(@Context HttpServerRequest request) {
        identity.logout(
                current.getPrincipal().getName(),
                request.remoteAddress() == null ? null : request.remoteAddress().hostAddress(),
                request.getHeader("User-Agent"));
        return Response.noContent().build();
    }

    private static List<String> sorted(Set<String> roles) {
        return roles.stream().sorted().toList();
    }
}
