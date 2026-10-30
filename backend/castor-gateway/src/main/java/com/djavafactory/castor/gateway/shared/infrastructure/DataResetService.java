package com.djavafactory.castor.gateway.shared.infrastructure;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.Interceptor;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.shared.application.StartupStep;

/**
 * The start-up and reset hook. With reset-on-start Flyway has already dropped and recreated schema ingest
 * (clean-at-start) by the time start-up reaches this bean; then, reset or not, the {@link StartupStep} beans run.
 */
@ApplicationScoped
public class DataResetService {

    private static final Logger LOG = Logger.getLogger(DataResetService.class);

    private final CastorConfig castor;
    private final Flyway flyway;
    private final Instance<StartupStep> steps;

    public DataResetService(CastorConfig castor, Flyway flyway, Instance<StartupStep> steps) {
        this.castor = castor;
        this.flyway = flyway;
        this.steps = steps;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER) StartupEvent event) {
        LOG.infof(
                "castor-gateway %s (%s) in %s, port %s",
                castor.build().version(), castor.build().commit(), castor.env(), castor.portCode());
        if (castor.data().resetOnStart()) {
            LOG.info("Dev data reset: schema ingest recreated");
        }
        runSteps();
    }

    /** Drops and recreates schema ingest, then runs the start-up steps: the same result as a fresh start. */
    public void reset() {
        ConfigGuard.verify(castor, ConfigProvider.getConfig());
        if (!castor.data().resetOnStart()) {
            throw new IllegalStateException("Data reset is not enabled (castor.data.reset-on-start=false)");
        }
        flyway.clean();
        flyway.migrate();
        runSteps();
    }

    private void runSteps() {
        steps.forEach(StartupStep::run);
    }
}
