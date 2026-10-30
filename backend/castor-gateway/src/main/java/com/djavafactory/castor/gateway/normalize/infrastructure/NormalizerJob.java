package com.djavafactory.castor.gateway.normalize.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.normalize.application.NormalizeInbox;
import com.djavafactory.castor.gateway.shared.domain.FailPoints;
import com.djavafactory.castor.gateway.shared.infrastructure.WorkersDisabled;

/** Runs the normalizer on a short interval. A run that fails is logged and tried again on the next tick. */
@ApplicationScoped
public class NormalizerJob {

    private static final Logger LOG = Logger.getLogger(NormalizerJob.class);

    private final NormalizeInbox normalizer;

    public NormalizerJob(NormalizeInbox normalizer) {
        this.normalizer = normalizer;
    }

    @Scheduled(
            identity = "normalizer",
            every = "{castor.normalizer.interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = WorkersDisabled.class)
    void tick() {
        try {
            normalizer.runOnce();
        } catch (FailPoints.FailPointException e) {
            LOG.debugf("%s", e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Normalizer run failed, trying again", e);
        }
    }
}
