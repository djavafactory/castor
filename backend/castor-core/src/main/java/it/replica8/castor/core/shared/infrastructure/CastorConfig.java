package it.replica8.castor.core.shared.infrastructure;

import java.time.Duration;
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

    /** Stamped at build time (META-INF/microprofile-config.properties); "unknown" when run without that file. */
    Build build();

    Features features();

    Admin admin();

    Data data();

    Security security();

    interface Build {
        /** X.Y.Z for a release tag, otherwise X.Y.Z-dev+commit. */
        @WithDefault("unknown")
        String version();

        /** Short git commit (7 characters), or "unknown" when the build had no git checkout. */
        @WithDefault("unknown")
        String commit();

        /** UTC, ISO-8601. */
        @WithDefault("unknown")
        String time();
    }

    interface Features {
        /** Container yards, STS cranes, RTGs and TEU figures. On for the Westports demo. */
        @WithDefault("true")
        boolean containers();
    }

    interface Admin {
        @WithDefault("admin")
        @NotBlank
        String username();

        @NotBlank
        String password();
    }

    interface Security {
        Lockout lockout();

        /** Create the first admin and the demo users at start. Off only for tests that run without a database. */
        @WithDefault("true")
        boolean bootstrapAccounts();

        /** How long a verified login is remembered so bcrypt does not run on every request. Never more than 60 s. */
        @WithDefault("PT60S")
        Duration authCacheTtl();
    }

    interface Lockout {
        /** Wrong passwords within the window that lock the account. */
        @WithDefault("5")
        int maxFailures();

        /** Counting window and lock duration. */
        @WithDefault("PT15M")
        Duration window();
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
