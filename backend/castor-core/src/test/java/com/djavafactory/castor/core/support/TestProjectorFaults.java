package com.djavafactory.castor.core.support;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.operations.application.IncomingRecord;
import com.djavafactory.castor.core.operations.application.ProjectorFaults;

/**
 * Breaks the projector on demand. Does nothing until a test sets a predicate; call {@link #reset()} when done. The
 * predicates are static because the bean is created by the container, not by the test.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestProjectorFaults extends ProjectorFaults {

    private static volatile Predicate<CanonicalEvent> failBeforeHandle = e -> false;
    private static volatile Predicate<IncomingRecord> failAfterCommit = r -> false;
    private static final AtomicInteger BEFORE_HANDLE_CALLS = new AtomicInteger();

    public static void failBeforeHandle(Predicate<CanonicalEvent> when) {
        failBeforeHandle = when;
    }

    public static void failAfterCommit(Predicate<IncomingRecord> when) {
        failAfterCommit = when;
    }

    /** How many times an event reached its handler's doorstep since the last reset. */
    public static int beforeHandleCalls() {
        return BEFORE_HANDLE_CALLS.get();
    }

    public static void reset() {
        failBeforeHandle = e -> false;
        failAfterCommit = r -> false;
        BEFORE_HANDLE_CALLS.set(0);
    }

    @Override
    public void beforeHandle(CanonicalEvent event) {
        BEFORE_HANDLE_CALLS.incrementAndGet();
        if (failBeforeHandle.test(event)) {
            throw new IllegalStateException("fail point before the handler of " + event.eventId());
        }
    }

    @Override
    public void afterCommit(IncomingRecord record) {
        if (failAfterCommit.test(record)) {
            throw new IllegalStateException("fail point after the commit of offset " + record.offset());
        }
    }
}
