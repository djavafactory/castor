package com.djavafactory.castor.core.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests start their own broker (Quarkus Dev Services) and never use the local or dev one. A KAFKA_BOOTSTRAP_SERVERS in
 * the environment, for example from the exported root .env, switches Dev Services off and would send the tests to
 * that broker, so the build stops here instead.
 */
class KafkaTestIsolationTest {

    @Test
    void noBrokerAddressIsExportedToTheTests() {
        String exported = System.getenv("KAFKA_BOOTSTRAP_SERVERS");

        assertTrue(
                exported == null || exported.isBlank(),
                "KAFKA_BOOTSTRAP_SERVERS is set (" + exported
                        + "). Unset it before running the backend tests: they start"
                        + " their own Kafka and must never connect to the local or dev broker.");
    }
}
