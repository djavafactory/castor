package it.replica8.castor.core.support;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Points the service at a database that does not exist. Migration at start and Hibernate are off, otherwise start-up
 * fails fast (the real behaviour when the database is down at start).
 */
public class NoDatabaseProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.devservices.enabled", "false",
                "quarkus.datasource.jdbc.url", "jdbc:postgresql://127.0.0.1:1/none",
                "quarkus.datasource.username", "nobody",
                "quarkus.datasource.password", "nothing",
                "quarkus.datasource.jdbc.acquisition-timeout", "2S",
                "quarkus.hibernate-orm.active", "false",
                "quarkus.flyway.migrate-at-start", "false");
    }
}
