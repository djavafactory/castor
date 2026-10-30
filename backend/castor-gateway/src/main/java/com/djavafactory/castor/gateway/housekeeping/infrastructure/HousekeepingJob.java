package com.djavafactory.castor.gateway.housekeeping.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.housekeeping.application.RunHousekeeping;
import com.djavafactory.castor.gateway.shared.infrastructure.WorkersDisabled;

/** Runs the housekeeping on its own interval (an hour by default). */
@ApplicationScoped
public class HousekeepingJob {

    private static final Logger LOG = Logger.getLogger(HousekeepingJob.class);

    private final RunHousekeeping housekeeping;

    public HousekeepingJob(RunHousekeeping housekeeping) {
        this.housekeeping = housekeeping;
    }

    @Scheduled(
            identity = "housekeeping",
            every = "{castor.retention.interval}",
            delay = 1,
            delayUnit = java.util.concurrent.TimeUnit.MINUTES,
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = WorkersDisabled.class)
    void tick() {
        try {
            housekeeping.runOnce();
        } catch (RuntimeException e) {
            LOG.error("Housekeeping run failed, trying again at the next interval", e);
        }
    }
}
