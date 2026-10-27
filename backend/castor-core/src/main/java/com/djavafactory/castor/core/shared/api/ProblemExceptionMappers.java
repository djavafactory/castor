package com.djavafactory.castor.core.shared.api;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.MissingIfMatchException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * Turns every exception into an RFC 9457 Problem Details response. Bodies never carry SQL, stack traces or class names.
 */
public class ProblemExceptionMappers {

    private static final Logger LOG = Logger.getLogger(ProblemExceptionMappers.class);

    @ServerExceptionMapper
    public Response badRequest(ConstraintViolationException e, UriInfo uri) {
        List<ProblemDetail.Error> errors = e.getConstraintViolations().stream()
                .map(ProblemExceptionMappers::toError)
                .toList();
        String detail = errors.size() + (errors.size() == 1 ? " constraint failed" : " constraints failed");
        return problem(400, "bad-request", "Invalid request", detail, uri, errors);
    }

    @ServerExceptionMapper
    public Response badRequest(JsonProcessingException e, UriInfo uri) {
        return problem(400, "bad-request", "Invalid request", "The request body is not valid JSON", uri, null);
    }

    @ServerExceptionMapper
    public Response badRequest(InvalidRequestException e, UriInfo uri) {
        return problem(400, "bad-request", "Invalid request", e.getMessage(), uri, null);
    }

    @ServerExceptionMapper
    public Response notFound(ResourceNotFoundException e, UriInfo uri) {
        return problem(404, "not-found", "Not found", e.getMessage(), uri, null);
    }

    @ServerExceptionMapper
    public Response conflict(ConflictException e, UriInfo uri) {
        return problem(409, "conflict", "Conflict", e.getMessage(), uri, null);
    }

    @ServerExceptionMapper
    public Response conflict(OptimisticLockException e, UriInfo uri) {
        return problem(
                409,
                "conflict",
                "Conflict",
                "The resource was changed by someone else, reload and try again",
                uri,
                null);
    }

    @ServerExceptionMapper
    public Response preconditionFailed(MissingIfMatchException e, UriInfo uri) {
        return problem(412, "precondition-failed", "Precondition failed", e.getMessage(), uri, null);
    }

    @ServerExceptionMapper
    public Response unprocessable(RuleViolationException e, UriInfo uri) {
        List<ProblemDetail.Error> errors =
                e.violations().stream().map(ProblemExceptionMappers::toError).toList();
        String detail = errors.size() + (errors.size() == 1 ? " rule failed" : " rules failed");
        return problem(422, "validation", e.title(), detail, uri, errors);
    }

    @ServerExceptionMapper
    public Response notFound(NotFoundException e, UriInfo uri) {
        return problem(404, "not-found", "Not found", "No resource at this address", uri, null);
    }

    @ServerExceptionMapper
    public Response webApplication(WebApplicationException e, UriInfo uri) {
        int status = e.getResponse().getStatus();
        if (status >= 500) {
            return unexpected(e, uri);
        }
        var known = ClientError.of(status);
        Response.ResponseBuilder builder =
                Response.fromResponse(problem(status, known.slug(), known.title(), known.detail(), uri, null));
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

    private static Response problem(
            int status, String slug, String title, String detail, UriInfo uri, List<ProblemDetail.Error> errors) {
        var body = new ProblemDetail(
                ProblemDetail.BASE_URI + slug,
                title,
                status,
                detail,
                uri.getRequestUri().getRawPath(),
                (String) MDC.get(RequestContextFilters.REQUEST_ID),
                null,
                errors);
        return Response.status(status)
                .type(ProblemDetail.MEDIA_TYPE)
                .entity(body)
                .build();
    }

    private static ProblemDetail.Error toError(ConstraintViolation<?> violation) {
        String code = violation
                .getConstraintDescriptor()
                .getAnnotation()
                .annotationType()
                .getSimpleName();
        return new ProblemDetail.Error(
                code.toUpperCase(), null, pointer(violation.getPropertyPath()), violation.getMessage());
    }

    private static ProblemDetail.Error toError(RuleViolation violation) {
        return new ProblemDetail.Error(violation.code(), violation.assetId(), violation.pointer(), violation.message());
    }

    /** JSON pointer to the failing field, without the method and parameter names Bean Validation adds. */
    private static String pointer(Path path) {
        var pointer = new StringBuilder();
        for (Path.Node node : path) {
            if (node.getKind() == ElementKind.METHOD || node.getKind() == ElementKind.PARAMETER) {
                continue;
            }
            if (node.getName() != null) {
                pointer.append('/').append(node.getName());
            }
        }
        return pointer.length() == 0 ? "/" : pointer.toString();
    }

    private record ClientError(String slug, String title, String detail) {

        static ClientError of(int status) {
            return switch (status) {
                case 400 -> new ClientError("bad-request", "Invalid request", "The request could not be understood");
                case 401 -> new ClientError("unauthorized", "Authentication required", "Sign in to continue");
                case 403 -> new ClientError("forbidden", "Forbidden", "You are not allowed to do this");
                case 404 -> new ClientError("not-found", "Not found", "No resource at this address");
                case 405 ->
                    new ClientError("method-not-allowed", "Method not allowed", "This method is not supported here");
                case 406 ->
                    new ClientError("not-acceptable", "Not acceptable", "No representation matches the Accept header");
                case 413 ->
                    new ClientError(
                            "payload-too-large", "Payload too large", "The request body exceeds the size limit");
                case 415 ->
                    new ClientError(
                            "unsupported-media-type",
                            "Unsupported media type",
                            "Send application/json or application/geo+json");
                default -> new ClientError("client-error", "Request failed", "The request could not be processed");
            };
        }
    }
}
