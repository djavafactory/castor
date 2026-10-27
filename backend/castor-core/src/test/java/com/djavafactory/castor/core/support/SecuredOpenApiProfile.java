package com.djavafactory.castor.core.support;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/** Applies the prod rule that OpenAPI and Swagger UI need the ADMIN role; dev leaves them open. */
public class SecuredOpenApiProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.http.auth.permission.openapi.policy", "admin-only");
    }
}
