package com.djavafactory.castor.core;

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
    void prodStopsWithAClearMessageWhenDatabaseUrlIsMissing() {
        assumeTrue(System.getenv("DB_URL") == null);

        var e = assertThrows(
                NoSuchElementException.class,
                () -> config("prod").getValue("quarkus.datasource.jdbc.url", String.class));

        assertEquals(true, e.getMessage().contains("DB_URL"), e.getMessage());
    }

    @Test
    void prodStopsWhenCorsOriginsAreMissing() {
        assumeTrue(System.getenv("CASTOR_CORS_ORIGINS") == null);

        var e = assertThrows(
                NoSuchElementException.class, () -> config("prod").getValue("quarkus.http.cors.origins", String.class));

        assertEquals(true, e.getMessage().contains("CASTOR_CORS_ORIGINS"), e.getMessage());
    }

    @Test
    void logsAreJsonInProdAndPlainInDev() {
        assertEquals(true, config("prod").getValue("quarkus.log.console.json.enabled", Boolean.class));
        assertEquals(false, config("dev").getValue("quarkus.log.console.json.enabled", Boolean.class));
        assertEquals(
                "DEBUG",
                config("dev").getValue("quarkus.log.category.\"com.djavafactory.castor\".level", String.class));
        assertEquals(
                "INFO",
                config("prod").getValue("quarkus.log.category.\"com.djavafactory.castor\".level", String.class));
    }

    @Test
    void devNeedsNoEnvironmentValues() {
        assumeTrue(System.getenv("CASTOR_CORS_ORIGINS") == null);

        var config = config("dev");

        assertEquals("http://localhost:5173", config.getValue("quarkus.http.cors.origins", String.class));
        assertEquals("castor-core", config.getValue("quarkus.application.name", String.class));
        assertEquals(8081, config.getValue("quarkus.http.port", Integer.class));
        assertEquals(9000, config.getValue("quarkus.management.port", Integer.class));
    }
}
