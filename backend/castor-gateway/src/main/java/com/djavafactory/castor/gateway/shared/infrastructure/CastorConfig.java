package com.djavafactory.castor.gateway.shared.infrastructure;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Every castor.* property of the gateway. Environment variables are mapped in application.properties. */
@ConfigMapping(prefix = "castor")
public interface CastorConfig {

    Set<String> KNOWN_ENVIRONMENTS = Set.of("local", "dev", "test");
    Set<String> RESET_ENVIRONMENTS = Set.of("dev", "test");

    /** local, dev or test. Anything else (staging, prod) is a protected environment. */
    String env();

    /** The port whose sources the demo bootstrap creates (local, dev and test). */
    String portCode();

    /** Stamped at build time (META-INF/microprofile-config.properties); "unknown" when run without that file. */
    Build build();

    Data data();

    Kafka kafka();

    Intake intake();

    Workers workers();

    Normalizer normalizer();

    Drop drop();

    Relay relay();

    Retention retention();

    /** Name of the {@code FailPoint} to trigger (CASTOR_FAIL_POINT). Refused outside local, dev and test. */
    Optional<String> failPoint();

    /** {@code throw} stops the worker run (default), {@code halt} ends the process like a crash would. */
    @WithDefault("throw")
    String failPointMode();

    interface Build {
        @WithDefault("unknown")
        String version();

        @WithDefault("unknown")
        String commit();

        @WithDefault("unknown")
        String time();
    }

    interface Data {
        /** Wipe schema ingest at start. Allowed only in dev and test. */
        @WithDefault("false")
        boolean resetOnStart();
    }

    interface Kafka {
        /** Replication factor of the topics created at start. 1 in local and dev (one broker). */
        @WithDefault("1")
        short replicationFactor();

        /** How long the broker may take to answer while the topics are created. */
        @WithDefault("PT30S")
        Duration topicsTimeout();

        /** How long the readiness check waits for the broker before it reports DOWN. */
        @WithDefault("PT3S")
        Duration healthTimeout();
    }

    interface Intake {
        /**
         * The API key of every source in local, dev and test. Written hashed at start. Never allowed in a protected
         * environment.
         */
        Optional<String> demoKey();

        /** Largest request body, in bytes. */
        @WithDefault("262144")
        long maxBodyBytes();

        /** Most records in one request. */
        @WithDefault("500")
        int maxBatchSize();

        /** Largest difference between X-Castor-Timestamp and the gateway clock. */
        @WithDefault("PT5M")
        Duration maxClockSkew();
    }

    interface Workers {
        /** Switches the scheduled normalizer, relay and housekeeping off; tests run them by hand. */
        @WithDefault("true")
        boolean enabled();
    }

    interface Drop {
        /**
         * The drop folder (GATEWAY_DROP_DIR): files are taken from {dir}/{source}/. Unset switches the file channel off.
         */
        Optional<String> dir();

        /** How often the folders are looked at. */
        @WithDefault("PT5S")
        Duration interval();
    }

    interface Normalizer {
        @WithDefault("200")
        int batchSize();

        @WithDefault("PT0.5S")
        Duration interval();
    }

    interface Relay {
        @WithDefault("500")
        int batchSize();

        @WithDefault("PT0.5S")
        Duration interval();

        /** How long the broker may take to acknowledge a message before the relay gives up until its next run. */
        @WithDefault("PT10S")
        Duration sendTimeout();
    }

    interface Retention {
        /** Published outbox rows are deleted after this time. */
        @WithDefault("P7D")
        Duration outbox();

        /** Raw payloads of NORMALIZED inbox rows are emptied after this time. */
        @WithDefault("P7D")
        Duration raw();

        /** Quarantine entries (and the raw payload of their record) are deleted after this time. */
        @WithDefault("P30D")
        Duration quarantine();

        @WithDefault("PT1H")
        Duration interval();
    }
}
