package com.djavafactory.castor.gateway;

import java.util.NoSuchElementException;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void prodStopsWithAClearMessageWhenTheDatabaseUrlIsMissing() {
        assumeTrue(System.getenv("DB_URL") == null);

        var e = assertThrows(
                NoSuchElementException.class,
                () -> config("prod").getValue("quarkus.datasource.jdbc.url", String.class));

        assertTrue(e.getMessage().contains("DB_URL"), e.getMessage());
    }

    @Test
    void prodNeedsTheGatewayRoleAndTheBroker() {
        assumeTrue(System.getenv("DB_GATEWAY_USERNAME") == null && System.getenv("KAFKA_BOOTSTRAP_SERVERS") == null);

        var username = assertThrows(
                NoSuchElementException.class,
                () -> config("prod").getValue("quarkus.datasource.username", String.class));
        var kafka = assertThrows(
                NoSuchElementException.class, () -> config("prod").getValue("kafka.bootstrap.servers", String.class));

        assertTrue(username.getMessage().contains("DB_GATEWAY_USERNAME"), username.getMessage());
        assertTrue(kafka.getMessage().contains("KAFKA_BOOTSTRAP_SERVERS"), kafka.getMessage());
    }

    @Test
    void logsAreJsonInProdAndPlainInDev() {
        assertEquals(true, config("prod").getValue("quarkus.log.console.json.enabled", Boolean.class));
        assertEquals(false, config("dev").getValue("quarkus.log.console.json.enabled", Boolean.class));
    }

    @Test
    void portsAreTheOnesTheComposeFileAndTheDocsName() {
        var config = config("dev");

        assertEquals("castor-gateway", config.getValue("quarkus.application.name", String.class));
        assertEquals(8082, config.getValue("quarkus.http.port", Integer.class));
        assertEquals(9002, config.getValue("quarkus.management.port", Integer.class));
    }

    @Test
    void devHasADemoKeyAndProdHasNone() {
        assumeTrue(System.getenv("GATEWAY_DEMO_KEY") == null);

        assertTrue(config("dev")
                .getOptionalValue("castor.intake.demo-key", String.class)
                .isPresent());
        assertTrue(config("prod")
                .getOptionalValue("castor.intake.demo-key", String.class)
                .isEmpty());
    }
}
