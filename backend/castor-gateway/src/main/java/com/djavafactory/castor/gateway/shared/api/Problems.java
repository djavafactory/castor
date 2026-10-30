package com.djavafactory.castor.gateway.shared.api;

import java.util.List;

import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.MDC;

/** Builds {@link ProblemDetail} bodies with the request id and path of the current request. */
public final class Problems {

    private Problems() {}

    public static ProblemDetail of(
            int status, String slug, String title, String detail, UriInfo uri, List<ProblemDetail.Error> errors) {
        return new ProblemDetail(
                ProblemDetail.BASE_URI + slug,
                title,
                status,
                detail,
                uri.getRequestUri().getRawPath(),
                (String) MDC.get(RequestContextFilters.REQUEST_ID),
                null,
                errors);
    }
}
