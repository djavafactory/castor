package com.djavafactory.castor.core.operations.infrastructure;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.application.RetryPause;

/** Waits on the consumer's own (blocking) thread. */
@ApplicationScoped
public class ThreadRetryPause implements RetryPause {

    @Override
    public void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
