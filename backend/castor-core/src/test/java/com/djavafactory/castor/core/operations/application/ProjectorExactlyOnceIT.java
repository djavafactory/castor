package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.core.support.ProjectionTestSupport;
import com.djavafactory.castor.core.support.ProjectionTestSupport.Layout;
import com.djavafactory.castor.core.support.TestEvents;
import com.djavafactory.castor.core.support.TestEvents.SteppedClock;
import com.djavafactory.castor.core.support.TestProjectorFaults;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Each canonical event changes state once, however often it is delivered and wherever the consumer stopped. */
@QuarkusTest
class ProjectorExactlyOnceIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    CanonicalEventProcessor processor;

    private Layout layout;
    private SteppedClock clock;
    private TestEvents events;

    @BeforeEach
    void setUp() {
        layout = ProjectionTestSupport.publishedLayout(dataSource, 4);
        clock = new SteppedClock(T0);
        events = new TestEvents(clock);
    }

    @AfterEach
    void tearDown() {
        TestProjectorFaults.reset();
        ProjectionTestSupport.clearState(dataSource);
    }

    private static IncomingRecord record(long offset, CanonicalEvent event) {
        return new IncomingRecord(Topics.INGEST_CANONICAL, 0, offset, event.aggregateId(), TestEvents.bytes(event));
    }

    /** 200 events for four cranes in a fixed order, a back-dated one now and then (these become stale items). */
    private List<CanonicalEvent> twoHundredEvents() {
        String[] statuses = {"WORKING", "IDLE", "BLOCKED", "STARVED", "DOWN", "MAINTENANCE"};
        List<CanonicalEvent> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            clock.step(2);
            UUID crane = layout.cranes().get(i % layout.cranes().size());
            CanonicalEvent event = switch (i % 4) {
                case 0 -> events.status(crane, statuses[(i / 4) % statuses.length], "step " + i);
                case 1 ->
                    events.position(
                            crane, ProjectionTestSupport.LON + i * 1e-6, ProjectionTestSupport.LAT, i % 360 + 0.5, 3);
                case 2 -> events.assignment(crane, "JOB-" + i, i % 8 == 2 ? "CALL-" + i : null);
                default -> events.status(crane, statuses[(i / 4 + 1) % statuses.length], null);
            };
            all.add(i % 17 == 16 ? events.at(event, event.occurredAt().minusSeconds(30)) : event);
        }
        return all;
    }

    @Test
    void theSameEventDeliveredTwiceChangesStateOnce() {
        CanonicalEvent event = events.status(layout.crane(), "WORKING", "lifting");

        processor.process(record(0, event));
        List<String> afterFirst = ProjectionTestSupport.equipmentFingerprint(dataSource);
        processor.process(record(1, event));

        assertEquals(afterFirst, ProjectionTestSupport.equipmentFingerprint(dataSource));
        assertEquals(1, count(dataSource, "select count(*) from ops.processed_event"));
        assertEquals(1, count(dataSource, "select count(*) from ops.canonical_event"));
        assertEquals(1, count(dataSource, "select count(*) from ops.state_change"));
        assertEquals(0, count(dataSource, "select version from ops.equipment_state"));
        assertEquals(1, count(dataSource, "select count(*) from ops.equipment_status_interval"));
    }

    @Test
    void aConsumerStoppedAfterTheCommitAndRestartedEndsWithTheStateOfAStraightRun() {
        List<CanonicalEvent> events = twoHundredEvents();

        for (int i = 0; i < events.size(); i++) {
            processor.process(record(i, events.get(i)));
        }
        List<String> straight = ProjectionTestSupport.equipmentFingerprint(dataSource);
        assertFalse(straight.isEmpty());
        assertEquals(200, count(dataSource, "select count(*) from ops.processed_event"));
        ProjectionTestSupport.clearState(dataSource);

        // stopped at offset 99: committed to the database, offset never acknowledged
        TestProjectorFaults.failAfterCommit(r -> r.offset() == 99);
        assertThrows(IllegalStateException.class, () -> {
            for (int i = 0; i < events.size(); i++) {
                processor.process(record(i, events.get(i)));
            }
        });
        TestProjectorFaults.reset();
        assertEquals(100, count(dataSource, "select count(*) from ops.processed_event"));

        // restarted: the broker delivers again from the last acknowledged offset, 98, so 99 arrives twice
        for (int i = 98; i < events.size(); i++) {
            processor.process(record(i, events.get(i)));
        }

        assertEquals(straight, ProjectionTestSupport.equipmentFingerprint(dataSource));
    }

    @Test
    void aFailureBeforeTheCommitLeavesNothingBehindAndTheRetryAppliesTheEventOnce() {
        CanonicalEvent event = events.status(layout.crane(), "DOWN", "leak");
        // the first attempt dies after the event was stored and marked processed: all of it is rolled back
        TestProjectorFaults.failBeforeHandle(e -> TestProjectorFaults.beforeHandleCalls() == 1);

        processor.process(record(0, event));

        assertEquals(2, TestProjectorFaults.beforeHandleCalls());
        assertEquals(1, count(dataSource, "select count(*) from ops.processed_event"));
        assertEquals(1, count(dataSource, "select count(*) from ops.canonical_event"));
        assertEquals(1, count(dataSource, "select count(*) from ops.state_change"));
        assertEquals(0, count(dataSource, "select count(*) from ops.dead_letter"));
        assertEquals(1, count(dataSource, "select count(*) from ops.equipment_state where status = 'DOWN'"));
    }
}
