package com.djavafactory.castor.gateway.intake.api;

import java.util.List;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import com.djavafactory.castor.gateway.intake.domain.IntakeException;
import com.djavafactory.castor.gateway.shared.api.ProblemDetail;
import com.djavafactory.castor.gateway.shared.api.Problems;

/** Every refusal of the intake endpoint is a Problem Details response with the status of its check. */
public class IntakeExceptionMapper {

    @ServerExceptionMapper
    public Response refused(IntakeException e, UriInfo uri) {
        IntakeException.Kind kind = e.kind();
        List<ProblemDetail.Error> errors = e.fields().isEmpty()
                ? null
                : e.fields().stream()
                        .map(f -> new ProblemDetail.Error(f.code(), f.pointer(), f.message()))
                        .toList();
        Response.ResponseBuilder response =
                Response.fromResponse(Problems.of(kind.status(), kind.slug(), kind.title(), e.getMessage(), uri, errors)
                        .toResponse());
        if (kind == IntakeException.Kind.UNAUTHORIZED) {
            response.header("WWW-Authenticate", "X-Castor-Key");
        }
        return response.build();
    }
}
