package com.djavafactory.castor.gateway.shared.infrastructure;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import org.eclipse.microprofile.config.ConfigProvider;

/** Skips every scheduled worker run while {@code castor.workers.enabled} is false (tests run the workers by hand). */
public class WorkersDisabled implements Scheduled.SkipPredicate {

    @Override
    public boolean test(ScheduledExecution execution) {
        return !ConfigProvider.getConfig().getValue("castor.workers.enabled", Boolean.class);
    }
}
