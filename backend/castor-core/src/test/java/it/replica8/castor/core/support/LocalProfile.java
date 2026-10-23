package it.replica8.castor.core.support;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Applies the local profile's open OpenAPI and Swagger UI rules to the test profile. The real local profile points at
 * the Docker database (application-local.properties), tests use Dev Services instead.
 */
public class LocalProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.http.auth.permission.openapi.policy", "permit",
                "quarkus.swagger-ui.always-include", "true",
                "quarkus.http.test-port", "0",
                "quarkus.management.test-port", "0");
    }
}
