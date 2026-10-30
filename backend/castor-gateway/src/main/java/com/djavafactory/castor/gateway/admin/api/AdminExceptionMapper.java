package com.djavafactory.castor.gateway.admin.api;

import java.util.List;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import com.djavafactory.castor.gateway.admin.domain.AdminException;
import com.djavafactory.castor.gateway.shared.api.ProblemDetail;
import com.djavafactory.castor.gateway.shared.api.Problems;

/** An admin refusal is Problem Details with the machine code of the refusal in {@code errors[0].code}. */
public class AdminExceptionMapper {

    @ServerExceptionMapper
    public Response refused(AdminException e, UriInfo uri) {
        var error = List.of(new ProblemDetail.Error(e.code(), null, e.getMessage()));
        return switch (e.kind()) {
            case NOT_FOUND ->
                Problems.of(404, "not-found", "Not found", e.getMessage(), uri, error)
                        .toResponse();
            case CONFLICT ->
                Problems.of(409, "conflict", "Conflict", e.getMessage(), uri, error)
                        .toResponse();
            case INVALID ->
                Problems.of(422, "validation-failed", "Invalid request", e.getMessage(), uri, error)
                        .toResponse();
        };
    }
}
