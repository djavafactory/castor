package com.djavafactory.castor.gateway.shared.api;

import java.util.List;

import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RFC 9457 Problem Details body, sent as application/problem+json. Same shape as castor-core's. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String requestId,
        String errorId,
        List<Error> errors) {

    public static final String MEDIA_TYPE = "application/problem+json";
    public static final String BASE_URI = "https://replica8.it/problems/";

    /** One failed rule or field. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String code, String pointer, String message) {}

    /** The response for this problem. */
    public Response toResponse() {
        return Response.status(status).type(MEDIA_TYPE).entity(this).build();
    }
}
