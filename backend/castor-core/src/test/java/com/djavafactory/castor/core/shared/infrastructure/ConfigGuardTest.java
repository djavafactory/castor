package com.djavafactory.castor.core.shared.infrastructure;

import java.util.List;
import java.util.Map;

import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigGuardTest {

    private static final String STRONG = "a-strong-password-1";
    private static final List<String> ORIGINS = List.of("https://castor.example.org");

    private static CastorConfig config(String env, boolean demo, boolean reset, String adminPassword) {
        return new SmallRyeConfigBuilder()
                .withMapping(CastorConfig.class)
                .withDefaultValues(Map.of(
                        "castor.env",
                        env,
                        "castor.port-code",
                        "westports",
                        "castor.demo-login",
                        String.valueOf(demo),
                        "castor.admin.password",
                        adminPassword,
                        "castor.data.reset-on-start",
                        String.valueOf(reset),
                        "castor.gateway.service-token",
                        "a-random-service-token"))
                .build()
                .getConfigMapping(CastorConfig.class);
    }

    private static String message(CastorConfig config, List<String> origins, Map<String, String> values) {
        return assertThrows(IllegalStateException.class, () -> ConfigGuard.verify(config, origins, values))
                .getMessage();
    }

    @Test
    void demoLoginDefaultsToOff() {
        var config = new SmallRyeConfigBuilder()
                .withMapping(CastorConfig.class)
                .withDefaultValues(
                        Map.of("castor.env", "local", "castor.port-code", "westports", "castor.admin.password", STRONG))
                .build()
                .getConfigMapping(CastorConfig.class);

        assertFalse(config.demoLogin());
        assertFalse(config.demoLoginActive());
        assertFalse(config.data().resetOnStart());
    }

    @Test
    void demoLoginIsActiveOnlyInLocalAndDev() {
        assertTrue(config("local", true, false, STRONG).demoLoginActive());
        assertTrue(config("dev", true, false, STRONG).demoLoginActive());
        assertFalse(config("test", true, false, STRONG).demoLoginActive());
        assertFalse(config("staging", true, false, STRONG).demoLoginActive());
        assertFalse(config("dev", false, false, STRONG).demoLoginActive());
    }

    @Test
    void demoLayoutIsSeededOnlyInLocalDevAndTestEvenWhenTheFlagIsOn() {
        // castor.layout.seed-on-start defaults to true in every profile
        assertTrue(config("local", false, false, STRONG).layoutSeedActive());
        assertTrue(config("dev", false, false, STRONG).layoutSeedActive());
        assertTrue(config("test", false, false, STRONG).layoutSeedActive());
        assertFalse(config("staging", false, false, STRONG).layoutSeedActive());
        assertFalse(config("prod", false, false, STRONG).layoutSeedActive());
    }

    @Test
    void stagingWithDemoLoginRefusesToStart() {
        var message = message(config("staging", true, false, STRONG), ORIGINS, Map.of());

        assertTrue(message.contains("CASTOR_ENV=staging") && message.contains("CASTOR_DEMO_LOGIN"), message);
    }

    @Test
    void protectedEnvironmentRefusesPlaceholdersShortPasswordsAndOpenCors() {
        var config = config("prod", false, false, "short");
        var message = message(config, List.of("*"), Map.of("castor.admin.password", "CHANGE_ME_AT_LEAST_14_CHARS"));

        assertTrue(message.contains("castor.admin.password still contains CHANGE_ME"), message);
        assertTrue(message.contains("at least 14 characters"), message);
        assertTrue(message.contains("CASTOR_CORS_ORIGINS"), message);
        assertTrue(message(config, List.of(), Map.of()).contains("CASTOR_CORS_ORIGINS"));
    }

    @Test
    void protectedEnvironmentWithSafeSettingsStarts() {
        assertDoesNotThrow(() -> ConfigGuard.verify(config("staging", false, false, STRONG), ORIGINS, Map.of()));
    }

    @Test
    void protectedEnvironmentNeedsTheServiceTokenOfTheInternalApis() {
        var withoutToken = new SmallRyeConfigBuilder()
                .withMapping(CastorConfig.class)
                .withDefaultValues(Map.of(
                        "castor.env", "staging", "castor.port-code", "westports", "castor.admin.password", STRONG))
                .build()
                .getConfigMapping(CastorConfig.class);

        assertTrue(message(withoutToken, ORIGINS, Map.of()).contains("CASTOR_SERVICE_TOKEN must be set"));
    }

    @Test
    void knownEnvironmentsAreNotHeldToTheProtectedRules() {
        assertDoesNotThrow(() -> ConfigGuard.verify(config("local", true, false, "short"), List.of(), Map.of()));
    }

    @Test
    void resetOnStartIsRefusedOutsideDevAndTest() {
        for (String env : List.of("local", "staging", "prod")) {
            assertEquals(
                    "Refusing to wipe data: reset-on-start is only allowed in dev or test",
                    message(config(env, false, true, STRONG), ORIGINS, Map.of()));
        }
        assertDoesNotThrow(() -> ConfigGuard.verify(config("dev", true, true, STRONG), ORIGINS, Map.of()));
        assertDoesNotThrow(() -> ConfigGuard.verify(config("test", false, true, STRONG), ORIGINS, Map.of()));
    }
}
