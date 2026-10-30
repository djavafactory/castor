package com.djavafactory.castor.gateway.shared.api;

import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Gives every request an id for the logs, returns it in X-Request-Id and writes one log line per finished request with
 * route, status and duration. Headers (the API key and the signature among them) and bodies are never logged.
 */
public class RequestContextFilters {

    public static final String REQUEST_ID = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final String STARTED_AT = "castor.startedAt";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger LOG = Logger.getLogger(RequestContextFilters.class);

    @ServerRequestFilter(preMatching = true, priority = 0)
    public void start(ContainerRequestContext request) {
        String incoming = request.getHeaderString(REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        request.setProperty(STARTED_AT, System.nanoTime());
        MDC.put(REQUEST_ID, requestId);
    }

    @ServerResponseFilter
    public void finish(ContainerRequestContext request, ContainerResponseContext response) {
        Object requestId = MDC.get(REQUEST_ID);
        if (requestId != null) {
            response.getHeaders().putSingle(REQUEST_ID_HEADER, requestId.toString());
        }
        long startedAt = request.getProperty(STARTED_AT) instanceof Long l ? l : System.nanoTime();
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        String route =
                request.getMethod() + " " + request.getUriInfo().getRequestUri().getRawPath();
        MDC.put("route", route);
        MDC.put("status", response.getStatus());
        MDC.put("durationMs", millis);
        try {
            LOG.infof("%s -> %d in %d ms", route, response.getStatus(), millis);
        } finally {
            MDC.remove("route");
            MDC.remove("status");
            MDC.remove("durationMs");
        }
    }
}
