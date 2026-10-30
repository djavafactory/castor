package com.djavafactory.castor.core.operations.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import org.jboss.logging.Logger;

import com.djavafactory.castor.core.operations.application.StateChangePublisher;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/** Runs {@link StateChangePublisher} every second on every core instance (an advisory lock keeps them from clashing). */
@ApplicationScoped
public class StateChangePublishJob {

    private static final Logger LOG = Logger.getLogger(StateChangePublishJob.class);

    private final StateChangePublisher publisher;

    StateChangePublishJob(StateChangePublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, skipExecutionIf = Disabled.class)
    void run() {
        try {
            publisher.publishPending();
        } catch (RuntimeException e) {
            LOG.warn("Publishing state changes failed; trying again on the next run", e);
        }
    }

    /** Off when castor.state.publisher.enabled is false (tests call the publisher themselves). */
    @ApplicationScoped
    public static class Disabled implements Scheduled.SkipPredicate {

        private final boolean enabled;

        Disabled(CastorConfig castor) {
            this.enabled = castor.state().publisher().enabled();
        }

        @Override
        public boolean test(ScheduledExecution execution) {
            return !enabled;
        }
    }
}
