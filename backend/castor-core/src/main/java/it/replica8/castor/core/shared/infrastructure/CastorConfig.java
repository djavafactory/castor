package it.replica8.castor.core.shared.infrastructure;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Every castor.* property. Environment variables are mapped in application.properties (CASTOR_ENV and friends). */
@ConfigMapping(prefix = "castor")
public interface CastorConfig {

    Set<String> DEMO_ENVIRONMENTS = Set.of("local", "dev");
    Set<String> KNOWN_ENVIRONMENTS = Set.of("local", "dev", "test");
    Set<String> RESET_ENVIRONMENTS = Set.of("dev", "test");

    /** local, dev or test. Anything else (staging, prod) is a protected environment. */
    @NotBlank
    String env();

    @NotBlank
    String portCode();

    /** Off unless explicitly true. Use {@link #demoLoginActive()} to decide whether demo users exist. */
    @WithDefault("false")
    boolean demoLogin();

    Admin admin();

    Data data();

    interface Admin {
        @WithDefault("admin")
        @NotBlank
        String username();

        @NotBlank
        String password();
    }

    interface Data {
        /** Wipe and reseed at start. Allowed only in dev and test. */
        @WithDefault("false")
        boolean resetOnStart();
    }

    /** Demo login and demo users need the flag AND a local or dev environment. */
    default boolean demoLoginActive() {
        return demoLogin() && DEMO_ENVIRONMENTS.contains(env());
    }
}
