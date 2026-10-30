package com.djavafactory.castor.core.support;

import java.time.Instant;

import javax.sql.DataSource;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.core.operations.application.CanonicalEventProcessor;
import com.djavafactory.castor.core.operations.application.IncomingRecord;
import com.djavafactory.castor.core.support.ProjectionTestSupport.Layout;
import com.djavafactory.castor.core.support.TestEvents.SteppedClock;

/**
 * What the projection integration tests share: a published layout, a clock they step by hand and the events it times,
 * and the way a record reaches the projector without going through the broker.
 */
public final class ProjectorHarness {

    public static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    public final Layout layout;
    public final SteppedClock clock = new SteppedClock(T0);
    public final TestEvents events = new TestEvents(clock);

    private final DataSource dataSource;
    private final CanonicalEventProcessor processor;
    private long offset;

    public ProjectorHarness(DataSource dataSource, CanonicalEventProcessor processor) {
        this.dataSource = dataSource;
        this.processor = processor;
        this.layout = ProjectionTestSupport.publishedLayout(dataSource, 1);
    }

    public void process(CanonicalEvent event) {
        processor.process(
                new IncomingRecord(Topics.INGEST_CANONICAL, 0, offset++, event.aggregateId(), TestEvents.bytes(event)));
    }

    public void close() {
        ProjectionTestSupport.clearState(dataSource);
    }
}
