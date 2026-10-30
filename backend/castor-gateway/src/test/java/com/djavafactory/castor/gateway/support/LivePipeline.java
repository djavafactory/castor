package com.djavafactory.castor.gateway.support;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * The gateway as it runs for real: the scheduled normalizer and relay are on (fast intervals), and the environment is
 * "test" with reset-on-start, as in the dev stack, so schema ingest is recreated at start.
 */
public class LivePipeline implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "castor.env", "test",
                "castor.data.reset-on-start", "true",
                "castor.workers.enabled", "true",
                "castor.normalizer.interval", "PT0.2S",
                "castor.relay.interval", "PT0.2S");
    }
}
