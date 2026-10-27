package com.djavafactory.castor.core.shared.infrastructure;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.Interceptor;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import com.djavafactory.castor.core.shared.application.DataSeedStep;

/**
 * The reset hook. With reset-on-start the schemas are dropped and recreated by Flyway (clean-at-start) and the seed
 * steps run once start-up reaches this bean. Every seed step plugs in as a {@link DataSeedStep}.
 */
@ApplicationScoped
public class DataResetService {

    private static final Logger LOG = Logger.getLogger(DataResetService.class);

    private final CastorConfig castor;
    private final Flyway flyway;
    private final Instance<DataSeedStep> steps;

    public DataResetService(CastorConfig castor, Flyway flyway, Instance<DataSeedStep> steps) {
        this.castor = castor;
        this.flyway = flyway;
        this.steps = steps;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER) StartupEvent event) {
        if (castor.data().resetOnStart()) {
            seed();
        }
    }

    /** Drops and recreates the schemas, then seeds: the same result as a fresh start. Refused unless enabled. */
    public void reset() {
        ConfigGuard.verify(castor, ConfigProvider.getConfig());
        if (!castor.data().resetOnStart()) {
            throw new IllegalStateException("Data reset is not enabled (castor.data.reset-on-start=false)");
        }
        flyway.clean();
        flyway.migrate();
        seed();
    }

    private void seed() {
        int assets = 0;
        for (DataSeedStep step : steps) {
            assets += step.seed();
        }
        LOG.infof("Dev data reset: schemas recreated and Westports seed loaded (%d assets, version 1)", assets);
    }
}
