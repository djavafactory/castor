package com.djavafactory.castor.gateway.shared.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;

/** Adds the hardening headers to every response, including errors raised before JAX-RS runs. */
@ApplicationScoped
public class SecurityHeaders {

    private static final int PRIORITY = 1000;

    void register(@Observes Filters filters) {
        filters.register(this::apply, PRIORITY);
    }

    private void apply(RoutingContext context) {
        var response = context.response();
        response.putHeader("X-Content-Type-Options", "nosniff");
        response.putHeader("X-Frame-Options", "DENY");
        response.putHeader("Referrer-Policy", "no-referrer");
        response.putHeader("Cache-Control", "no-store");
        if (isHttps(context)) {
            response.putHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        context.next();
    }

    private static boolean isHttps(RoutingContext context) {
        var request = context.request();
        return request.isSSL() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
