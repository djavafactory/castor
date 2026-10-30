package com.djavafactory.castor.gateway.shared.infrastructure;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.microprofile.config.Config;

import com.djavafactory.castor.gateway.shared.domain.FailPoint;

/** Start-up guards: unsafe configuration stops the service with a message that names the setting. */
public final class ConfigGuard {

    static final String PLACEHOLDER = "CHANGE_ME";
    static final String RESET_REFUSED = "Refusing to wipe data: reset-on-start is only allowed in dev or test";

    private ConfigGuard() {}

    /** Reads the settings the guards look at from the live configuration, then checks them. */
    public static void verify(CastorConfig castor, Config config) {
        var values = new TreeMap<String, String>();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("castor.") || name.startsWith("gateway.") || name.startsWith("quarkus.datasource.")) {
                try {
                    config.getOptionalValue(name, String.class).ifPresent(v -> values.put(name, v));
                } catch (RuntimeException ignored) {
                    // an unresolvable value is reported by the config system itself
                }
            }
        }
        verify(castor, values);
    }

    static void verify(CastorConfig castor, Map<String, String> values) {
        String env = castor.env();
        if (castor.data().resetOnStart() && !CastorConfig.RESET_ENVIRONMENTS.contains(env)) {
            throw new IllegalStateException(RESET_REFUSED);
        }
        castor.failPoint().ifPresent(name -> {
            if (FailPoint.fromCode(name).isEmpty()) {
                throw new IllegalStateException("CASTOR_FAIL_POINT names no fail point: " + name);
            }
        });
        if (!"throw".equals(castor.failPointMode()) && !"halt".equals(castor.failPointMode())) {
            throw new IllegalStateException("castor.fail-point-mode must be throw or halt");
        }
        if (CastorConfig.KNOWN_ENVIRONMENTS.contains(env)) {
            return;
        }
        var problems = new ArrayList<String>();
        if (castor.intake().demoKey().isPresent()) {
            problems.add("GATEWAY_DEMO_KEY must not be set");
        }
        if (castor.failPoint().isPresent()) {
            problems.add("CASTOR_FAIL_POINT must not be set");
        }
        if (castor.admin().serviceToken().filter(token -> !token.isBlank()).isEmpty()) {
            problems.add("CASTOR_SERVICE_TOKEN must be set");
        }
        values.forEach((name, value) -> {
            if (value.contains(PLACEHOLDER)) {
                problems.add(name + " still contains " + PLACEHOLDER);
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe configuration for CASTOR_ENV=" + env + ": " + String.join("; ", problems));
        }
    }
}
