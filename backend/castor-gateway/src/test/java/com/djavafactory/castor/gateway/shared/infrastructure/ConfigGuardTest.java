package com.djavafactory.castor.gateway.shared.infrastructure;

import java.util.HashMap;
import java.util.Map;

import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigGuardTest {

    private static CastorConfig config(String env, Map<String, String> extra) {
        var values = new HashMap<String, String>();
        values.put("castor.env", env);
        values.put("castor.port-code", "westports");
        values.putAll(extra);
        return new SmallRyeConfigBuilder()
                .withMapping(CastorConfig.class)
                .withDefaultValues(values)
                .build()
                .getConfigMapping(CastorConfig.class);
    }

    private static CastorConfig config(String env) {
        return config(env, Map.of());
    }

    private static String message(CastorConfig config, Map<String, String> values) {
        return assertThrows(IllegalStateException.class, () -> ConfigGuard.verify(config, values))
                .getMessage();
    }

    @Test
    void defaultsAreTheSafeOnes() {
        var config = config("local");

        assertFalse(config.data().resetOnStart());
        assertTrue(config.intake().demoKey().isEmpty());
        assertEquals(262_144, config.intake().maxBodyBytes());
        assertEquals(500, config.intake().maxBatchSize());
        assertEquals(java.time.Duration.ofMinutes(5), config.intake().maxClockSkew());
        assertEquals(200, config.normalizer().batchSize());
        assertEquals(java.time.Duration.ofDays(7), config.retention().outbox());
        assertEquals(java.time.Duration.ofDays(7), config.retention().raw());
        assertEquals(java.time.Duration.ofDays(30), config.retention().quarantine());
        assertEquals("unknown", config.build().version());
        assertEquals("throw", config.failPointMode());
    }

    @Test
    void resetOnStartIsRefusedOutsideDevAndTest() {
        for (String env : new String[] {"local", "staging", "prod"}) {
            assertEquals(
                    "Refusing to wipe data: reset-on-start is only allowed in dev or test",
                    message(config(env, Map.of("castor.data.reset-on-start", "true")), Map.of()));
        }
        for (String env : new String[] {"dev", "test"}) {
            assertDoesNotThrow(
                    () -> ConfigGuard.verify(config(env, Map.of("castor.data.reset-on-start", "true")), Map.of()));
        }
    }

    @Test
    void aProtectedEnvironmentRefusesTheDemoKeyAFailPointAndPlaceholders() {
        var config = config("staging", Map.of("castor.intake.demo-key", "abc", "castor.fail-point", "before-relay"));

        String message = message(config, Map.of("quarkus.datasource.password", "CHANGE_ME_DB"));

        assertTrue(message.contains("CASTOR_ENV=staging"), message);
        assertTrue(message.contains("GATEWAY_DEMO_KEY"), message);
        assertTrue(message.contains("CASTOR_FAIL_POINT"), message);
        assertTrue(message.contains("quarkus.datasource.password still contains CHANGE_ME"), message);
    }

    @Test
    void aProtectedEnvironmentWithSafeSettingsStarts() {
        var config = config("staging", Map.of("castor.admin.service-token", "a-random-token"));

        assertDoesNotThrow(() -> ConfigGuard.verify(config, Map.of("castor.relay.batch-size", "500")));
    }

    @Test
    void knownEnvironmentsAreNotHeldToTheProtectedRules() {
        var config = config("local", Map.of("castor.intake.demo-key", "CHANGE_ME_GATEWAY_KEY"));

        assertDoesNotThrow(() -> ConfigGuard.verify(config, Map.of("castor.intake.demo-key", "CHANGE_ME_GATEWAY_KEY")));
    }

    @Test
    void aFailPointMustExistAndItsModeMustBeKnown() {
        assertTrue(message(config("dev", Map.of("castor.fail-point", "nowhere")), Map.of())
                .contains("nowhere"));
        assertTrue(message(config("dev", Map.of("castor.fail-point-mode", "explode")), Map.of())
                .contains("throw or halt"));
        assertDoesNotThrow(() -> ConfigGuard.verify(
                config("dev", Map.of("castor.fail-point", "after-send-before-mark", "castor.fail-point-mode", "halt")),
                Map.of()));
    }
}
