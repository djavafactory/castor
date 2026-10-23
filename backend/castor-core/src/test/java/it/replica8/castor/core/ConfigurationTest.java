package it.replica8.castor.core;

import java.util.NoSuchElementException;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reads application.properties the way the service does, without starting it. */
class ConfigurationTest {

    private static SmallRyeConfig config(String profile) {
        return new SmallRyeConfigBuilder()
                .addDefaultSources()
                .addDiscoveredSources()
                .withProfile(profile)
                .build();
    }

    @Test
    void devEnvStopsWithAClearMessageWhenDatabaseUrlIsMissing() {
        assumeTrue(System.getenv("DB_URL") == null);

        var e = assertThrows(
                NoSuchElementException.class,
                () -> config("dev-env").getValue("quarkus.datasource.jdbc.url", String.class));

        assertEquals(true, e.getMessage().contains("DB_URL"), e.getMessage());
    }

    @Test
    void devEnvStopsWhenCorsOriginsAreMissing() {
        assumeTrue(System.getenv("CASTOR_CORS_ORIGINS") == null);

        var e = assertThrows(
                NoSuchElementException.class,
                () -> config("dev-env").getValue("quarkus.http.cors.origins", String.class));

        assertEquals(true, e.getMessage().contains("CASTOR_CORS_ORIGINS"), e.getMessage());
    }

    @Test
    void logsAreJsonOutsideLocalAndPlainInLocal() {
        assertEquals(true, config("dev-env").getValue("quarkus.log.console.json.enabled", Boolean.class));
        assertEquals(true, config("prod").getValue("quarkus.log.console.json.enabled", Boolean.class));
        assertEquals(false, config("local").getValue("quarkus.log.console.json.enabled", Boolean.class));
        assertEquals(
                "DEBUG", config("local").getValue("quarkus.log.category.\"it.replica8.castor\".level", String.class));
        assertEquals(
                "INFO", config("dev-env").getValue("quarkus.log.category.\"it.replica8.castor\".level", String.class));
    }

    @Test
    void localNeedsNoEnvironmentValues() {
        assumeTrue(System.getenv("CASTOR_CORS_ORIGINS") == null);

        var config = config("local");

        assertEquals("http://localhost:5173", config.getValue("quarkus.http.cors.origins", String.class));
        assertEquals("castor-core", config.getValue("quarkus.application.name", String.class));
        assertEquals(8081, config.getValue("quarkus.http.port", Integer.class));
        assertEquals(9000, config.getValue("quarkus.management.port", Integer.class));
    }
}
