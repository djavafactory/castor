package com.djavafactory.castor.gateway.relay.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.relay.application.RelayOutbox;
import com.djavafactory.castor.gateway.shared.domain.FailPoints;
import com.djavafactory.castor.gateway.shared.infrastructure.WorkersDisabled;

/** Runs the outbox relay on a short interval. A run that fails is logged and tried again on the next tick. */
@ApplicationScoped
public class RelayJob {

    private static final Logger LOG = Logger.getLogger(RelayJob.class);

    private final RelayOutbox relay;

    public RelayJob(RelayOutbox relay) {
        this.relay = relay;
    }

    @Scheduled(
            identity = "relay",
            every = "{castor.relay.interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = WorkersDisabled.class)
    void tick() {
        try {
            relay.runOnce();
        } catch (FailPoints.FailPointException e) {
            LOG.debugf("%s", e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Outbox relay run failed, trying again", e);
        }
    }
}
