package it.replica8.castor.core.support;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/** Runs the service with the local profile, where OpenAPI and Swagger UI are open. */
public class LocalProfile implements QuarkusTestProfile {

    @Override
    public String getConfigProfile() {
        return "local";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.http.test-port", "0", "quarkus.management.test-port", "0");
    }
}
