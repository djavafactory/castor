package com.djavafactory.castor.core.operations.application;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * Gives each record of the canonical topic exactly one effect. {@link #process} returns when the event has been applied,
 * was already applied, or has been dead-lettered; only then may the consumer acknowledge the offset, so a crash before
 * that delivers the record again and the processed-event guard turns the second delivery into a no-op. An event that
 * fails is tried again (bounded, with backoff) and then dead-lettered, so one bad event never blocks its partition.
 */
@ApplicationScoped
public class CanonicalEventProcessor {

    private static final Logger LOG = Logger.getLogger(CanonicalEventProcessor.class);

    private final EventApplier applier;
    private final DeadLetters deadLetters;
    private final ProjectorFaults faults;
    private final RetryPause pause;
    private final int attempts;
    private final List<Duration> backoff;

    CanonicalEventProcessor(
            EventApplier applier,
            DeadLetters deadLetters,
            ProjectorFaults faults,
            RetryPause pause,
            CastorConfig castor) {
        this.applier = applier;
        this.deadLetters = deadLetters;
        this.faults = faults;
        this.pause = pause;
        this.attempts = Math.max(1, castor.state().projector().attempts());
        this.backoff = castor.state().projector().backoff();
    }

    public void process(IncomingRecord record) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                applier.apply(record);
            } catch (RejectedEventException e) {
                // retrying cannot change the verdict
                LOG.debugf(
                        "Rejected %s-%d@%d: %s %s",
                        record.topic(), record.partition(), record.offset(), e.reason(), e.getMessage());
                deadLetters.record(record, e.reason(), e.getMessage(), attempt);
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.warnf(
                        e,
                        "Attempt %d of %d failed for %s-%d@%d",
                        attempt,
                        attempts,
                        record.topic(),
                        record.partition(),
                        record.offset());
                if (attempt < attempts && !backoff.isEmpty()) {
                    pause.pause(backoff.get(Math.min(attempt, backoff.size()) - 1));
                }
                continue;
            }
            faults.afterCommit(record);
            return;
        }
        deadLetters.record(record, DeadLetterReason.PROCESSING_FAILED, describe(last), attempts);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
