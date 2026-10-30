package com.djavafactory.castor.gateway.mapping.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.scheduler.Scheduled;

import com.djavafactory.castor.gateway.shared.infrastructure.WorkersDisabled;

/** Finishes the mapping seed once castor-core has published its layout; does nothing when the seed is done. */
@ApplicationScoped
public class MappingSeedJob {

    private final MappingSeedStep step;

    public MappingSeedJob(MappingSeedStep step) {
        this.step = step;
    }

    @Scheduled(
            identity = "mapping-seed",
            every = "15s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = WorkersDisabled.class)
    void tick() {
        step.retryIfPending();
    }
}
