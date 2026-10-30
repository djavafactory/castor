package com.djavafactory.castor.core.shared.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

    Reference reference();

    Layout layout();

    Kafka kafka();

    Gateway gateway();

    State state();

    interface State {
        Sla sla();

        Stream stream();

        Snapshot snapshot();

        Projector projector();

        Publisher publisher();
    }

    /** The consumer that turns canonical events into state (STATE-03). */
    interface Projector {
        /** Tries per event before it becomes a dead letter. */
        @WithDefault("3")
        int attempts();

        /** Wait after the 1st, 2nd, ... failed attempt; the last value repeats when there are more attempts. */
        @WithDefault("PT0.2S,PT1S,PT5S")
        List<Duration> backoff();
    }

    /** The relay of state changes to Kafka for consumers other than the browser hub. */
    interface Publisher {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("500")
        int batch();
    }

    /** How long an observed value stays fresh (R3), per kind of entity; sent with every item of the snapshot. */
    interface Sla {
        @WithDefault("PT120S")
        Duration equipment();

        @WithDefault("PT15M")
        Duration vesselCall();

        @WithDefault("PT15M")
        Duration inventory();

        @WithDefault("PT15M")
        Duration gateVisit();
    }

    interface Stream {
        /** How long a stream ticket can be redeemed. */
        @WithDefault("PT30S")
        Duration ticketTtl();

        /** Interval of the heartbeat message and of the check that a session's user may still read. */
        @WithDefault("PT15S")
        Duration heartbeat();

        /** Messages one session may have waiting before it is closed with 4001 "resync". */
        @WithDefault("1000")
        int maxQueue();
    }

    interface Snapshot {
        /** Departed and cancelled vessel calls stay in the snapshot this long after their last change. */
        @WithDefault("PT24H")
        Duration recentCalls();
    }

    interface Gateway {
        /** Base URL of castor-gateway's API (GATEWAY_URL); core calls its internal admin API there, never a browser. */
        @WithDefault("http://localhost:8082")
        String url();

        /**
         * The shared secret of the internal APIs (CASTOR_SERVICE_TOKEN), sent to the gateway as a bearer token. Required
         * in a protected environment.
         */
        Optional<String> serviceToken();

        /** How long one call to the gateway may take before it is reported as unavailable. */
        @WithDefault("PT10S")
        Duration timeout();

        @WithDefault("PT3S")
        Duration connectTimeout();
    }

    interface Kafka {
        /** Replication factor of the topics created at start. 1 in local and dev (one broker). */
        @WithDefault("1")
        short replicationFactor();

        /** How long the broker may take to answer while the topics are created; start-up fails after that. */
        @WithDefault("PT30S")
        Duration topicsTimeout();

        /** How long the readiness check waits for the broker before it reports DOWN. */
        @WithDefault("PT3S")
        Duration healthTimeout();
    }

    interface Layout {
        /**
         * Load the Westports demo layout (published version 1) at start when the port has no layout version. Only ever
         * honoured in local, dev and test; a protected environment never gets demo data.
         */
        @WithDefault("true")
        boolean seedOnStart();
    }

    interface Reference {
        /** Import the committed OpenStreetMap reference file at start (idempotent). */
        @WithDefault("true")
        boolean importOnStart();
    }

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

    /** The demo layout is loaded only when asked for AND in local, dev or test: never in a protected environment. */
    default boolean layoutSeedActive() {
        return layout().seedOnStart() && KNOWN_ENVIRONMENTS.contains(env());
    }
}
