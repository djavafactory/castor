package com.djavafactory.castor.core.shared.infrastructure;

import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MicrometerOperationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerOperationMetrics metrics = new MicrometerOperationMetrics(registry);

    @Test
    void metersExistBeforeTheFirstEvent() {
        assertNotNull(registry.find("castor.validation.duration").timer());
        assertNotNull(registry.find("castor.publish.count").counter());
        assertNotNull(registry.find("castor.import.count").counter());
        assertNotNull(registry.find("castor.spatial.query.duration").timer());
    }

    @Test
    void eventsAreCounted() {
        metrics.layoutPublished();
        metrics.importFinished();
        metrics.importFinished();
        metrics.validationFinished(Duration.ofMillis(5));
        metrics.spatialQueryFinished(Duration.ofMillis(7));

        assertEquals(1, registry.get("castor.publish.count").counter().count());
        assertEquals(2, registry.get("castor.import.count").counter().count());
        assertEquals(1, registry.get("castor.validation.duration").timer().count());
        assertEquals(1, registry.get("castor.spatial.query.duration").timer().count());
    }
}
