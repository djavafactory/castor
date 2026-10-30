package com.djavafactory.castor.gateway.shared.api;

import java.util.List;
import java.util.UUID;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns every exception that no resource handles itself into an RFC 9457 Problem Details response. Bodies never carry
 * SQL, stack traces or class names; a 500 carries only an error id, and the stack trace is in the log under that id.
 */
public class ProblemExceptionMappers {

    private static final Logger LOG = Logger.getLogger(ProblemExceptionMappers.class);

    @ServerExceptionMapper
    public Response notFound(NotFoundException e, UriInfo uri) {
        return Problems.of(404, "not-found", "Not found", "No resource at this address", uri, null)
                .toResponse();
    }

    @ServerExceptionMapper
    public Response webApplication(WebApplicationException e, UriInfo uri) {
        int status = e.getResponse().getStatus();
        if (status >= 500) {
            return unexpected(e, uri);
        }
        var known = ClientError.of(status);
        Response.ResponseBuilder builder =
                Response.fromResponse(Problems.of(status, known.slug(), known.title(), known.detail(), uri, null)
                        .toResponse());
        for (String header : List.of("Allow", "WWW-Authenticate")) {
            String value = e.getResponse().getHeaderString(header);
            if (value != null) {
                builder.header(header, value);
            }
        }
        return builder.build();
    }

    @ServerExceptionMapper
    public Response unexpected(Throwable e, UriInfo uri) {
        String errorId = UUID.randomUUID().toString();
        MDC.put("errorId", errorId);
        try {
            LOG.errorf(
                    e,
                    "Unexpected error errorId=%s path=%s",
                    errorId,
                    uri.getRequestUri().getRawPath());
        } finally {
            MDC.remove("errorId");
        }
        var body = new ProblemDetail(
                ProblemDetail.BASE_URI + "internal", "Unexpected error", 500, null, null, null, errorId, null);
        return Response.status(500).type(ProblemDetail.MEDIA_TYPE).entity(body).build();
    }

    private record ClientError(String slug, String title, String detail) {

        static ClientError of(int status) {
            return switch (status) {
                case 400 -> new ClientError("bad-request", "Invalid request", "The request could not be understood");
                case 401 -> new ClientError("unauthorized", "Authentication required", "Send a valid API key");
                case 403 -> new ClientError("forbidden", "Forbidden", "You are not allowed to do this");
                case 404 -> new ClientError("not-found", "Not found", "No resource at this address");
                case 405 ->
                    new ClientError("method-not-allowed", "Method not allowed", "This method is not supported here");
                case 406 ->
                    new ClientError("not-acceptable", "Not acceptable", "No representation matches the Accept header");
                case 413 ->
                    new ClientError(
                            "payload-too-large", "Payload too large", "The request body exceeds the size limit");
                case 415 -> new ClientError("unsupported-media-type", "Unsupported media type", "Send JSON");
                default -> new ClientError("client-error", "Request failed", "The request could not be processed");
            };
        }
    }
}
