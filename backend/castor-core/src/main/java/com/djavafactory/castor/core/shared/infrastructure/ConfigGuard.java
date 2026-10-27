package com.djavafactory.castor.core.shared.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.microprofile.config.Config;

/** Start-up guards: unsafe configuration stops the service with a message that names the setting. */
public final class ConfigGuard {

    static final String PLACEHOLDER = "CHANGE_ME";
    static final int MIN_ADMIN_PASSWORD_LENGTH = 14;
    static final String RESET_REFUSED = "Refusing to wipe data: reset-on-start is only allowed in dev or test";

    private ConfigGuard() {}

    /** Reads the settings the guards look at from the live configuration, then checks them. */
    public static void verify(CastorConfig castor, Config config) {
        var values = new TreeMap<String, String>();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("castor.") || name.startsWith("quarkus.datasource.")) {
                try {
                    config.getOptionalValue(name, String.class).ifPresent(v -> values.put(name, v));
                } catch (RuntimeException ignored) {
                    // an unresolvable value is reported by the config system itself
                }
            }
        }
        var origins = config.getOptionalValues("quarkus.http.cors.origins", String.class)
                .orElse(List.of());
        verify(castor, origins, values);
    }

    static void verify(CastorConfig castor, List<String> corsOrigins, Map<String, String> values) {
        String env = castor.env();
        if (castor.data().resetOnStart() && !CastorConfig.RESET_ENVIRONMENTS.contains(env)) {
            throw new IllegalStateException(RESET_REFUSED);
        }
        if (CastorConfig.KNOWN_ENVIRONMENTS.contains(env)) {
            return;
        }
        var problems = new ArrayList<String>();
        if (castor.demoLogin()) {
            problems.add("CASTOR_DEMO_LOGIN must not be true");
        }
        values.forEach((name, value) -> {
            if (value.contains(PLACEHOLDER)) {
                problems.add(name + " still contains " + PLACEHOLDER);
            }
        });
        if (castor.admin().password().length() < MIN_ADMIN_PASSWORD_LENGTH) {
            problems.add("CASTOR_ADMIN_PASSWORD must have at least " + MIN_ADMIN_PASSWORD_LENGTH + " characters");
        }
        if (corsOrigins.isEmpty() || corsOrigins.stream().anyMatch(o -> o.isBlank() || o.contains("*"))) {
            problems.add("CASTOR_CORS_ORIGINS must list explicit origins, not empty or *");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe configuration for CASTOR_ENV=" + env + ": " + String.join("; ", problems));
        }
    }
}
