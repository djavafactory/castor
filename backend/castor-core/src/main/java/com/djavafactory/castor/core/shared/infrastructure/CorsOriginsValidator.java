package com.djavafactory.castor.core.shared.infrastructure;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Stops start-up when the CORS allow-list is empty or contains a wildcard. */
@ApplicationScoped
public class CorsOriginsValidator {

    @ConfigProperty(name = "quarkus.http.cors.origins")
    Optional<List<String>> origins;

    void onStart(@Observes StartupEvent event) {
        validate(origins.orElse(List.of()));
    }

    static void validate(List<String> origins) {
        if (origins.isEmpty()) {
            throw new IllegalStateException("CASTOR_CORS_ORIGINS must list at least one allowed origin");
        }
        if (origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("CASTOR_CORS_ORIGINS must not contain a wildcard, list explicit origins");
        }
    }
}
