package com.djavafactory.castor.gateway.admin.api;

import java.util.Optional;

import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import com.djavafactory.castor.gateway.admin.application.ServiceToken;
import com.djavafactory.castor.gateway.shared.api.ProblemDetail;
import com.djavafactory.castor.gateway.shared.api.Problems;

/**
 * The internal admin API (everything under {@code /internal/}) is for castor-core only: it answers a request that
 * carries the service token as {@code Authorization: Bearer <token>} and refuses every other. It is never routed through
 * the frontend's nginx (rule R7), and without a configured token it refuses everything. Intake paths are not affected:
 * they authenticate with a source's own API key.
 */
public class ServiceTokenFilter {

    static final String PREFIX = "/internal/";

    private final ServiceToken token;

    public ServiceTokenFilter(ServiceToken token) {
        this.token = token;
    }

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION)
    public Optional<Response> check(ContainerRequestContext request) {
        if (!request.getUriInfo().getRequestUri().getRawPath().startsWith(PREFIX)
                || token.accepts(request.getHeaderString(HttpHeaders.AUTHORIZATION))) {
            return Optional.empty();
        }
        var problem = Problems.of(
                401,
                "unauthorized",
                "Authentication required",
                "Send the service token as Authorization: Bearer",
                request.getUriInfo(),
                null);
        return Optional.of(Response.status(401)
                .type(ProblemDetail.MEDIA_TYPE)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .entity(problem)
                .build());
    }
}
