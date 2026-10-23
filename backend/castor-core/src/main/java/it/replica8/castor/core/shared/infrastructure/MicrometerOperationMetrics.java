package it.replica8.castor.core.shared.infrastructure;

import java.time.Duration;

import jakarta.inject.Singleton;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.Startup;

import it.replica8.castor.core.shared.application.OperationMetrics;

/** Registers the Castor meters at start-up so they show in /q/metrics from the first scrape. */
@Startup
@Singleton
public class MicrometerOperationMetrics implements OperationMetrics {

    private final Timer validation;
    private final Counter published;
    private final Counter imported;
    private final Timer spatialQuery;

    public MicrometerOperationMetrics(MeterRegistry registry) {
        this.validation = Timer.builder("castor.validation.duration")
                .description("Time spent validating a layout")
                .register(registry);
        this.published = Counter.builder("castor.publish.count")
                .description("Layouts published")
                .register(registry);
        this.imported = Counter.builder("castor.import.count")
                .description("GeoJSON imports completed")
                .register(registry);
        this.spatialQuery = Timer.builder("castor.spatial.query.duration")
                .description("Time spent in spatial queries")
                .register(registry);
    }

    @Override
    public void validationFinished(Duration duration) {
        validation.record(duration);
    }

    @Override
    public void layoutPublished() {
        published.increment();
    }

    @Override
    public void importFinished() {
        imported.increment();
    }

    @Override
    public void spatialQueryFinished(Duration duration) {
        spatialQuery.record(duration);
    }
}
