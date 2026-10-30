package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.operations.domain.VesselStatus;
import com.djavafactory.castor.core.support.ProjectionTestSupport;
import com.djavafactory.castor.core.support.ProjectionTestSupport.Layout;
import com.djavafactory.castor.core.support.TestEvents;
import com.djavafactory.castor.core.support.TestEvents.SteppedClock;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static com.djavafactory.castor.core.support.ProjectionTestSupport.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The equipment and vessel call projections against the database, with a stepped clock giving the events their times. */
@QuarkusTest
class ProjectorStateIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    @Inject
    DataSource dataSource;

    @Inject
    CanonicalEventProcessor processor;

    @Inject
    EquipmentRepository equipment;

    @Inject
    VesselCallRepository calls;

    @Inject
    StateChangeRepository changes;

    @Inject
    StateViews views;

    @Inject
    ObjectMapper json;

    private Layout layout;
    private SteppedClock clock;
    private TestEvents events;
    private long offset;

    @BeforeEach
    void setUp() {
        layout = ProjectionTestSupport.publishedLayout(dataSource, 1);
        clock = new SteppedClock(T0);
        events = new TestEvents(clock);
        offset = 0;
    }

    @AfterEach
    void tearDown() {
        ProjectionTestSupport.clearState(dataSource);
    }

    private void process(CanonicalEvent event) {
        processor.process(
                new IncomingRecord(Topics.INGEST_CANONICAL, 0, offset++, event.aggregateId(), TestEvents.bytes(event)));
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }

    @Test
    void statusEventsKeepOneIntervalPerStretchAndCloseTheOpenOneAtTheNextChange() {
        process(events.status(layout.crane(), "WORKING", null));
        clock.step(600);
        process(events.status(layout.crane(), "BLOCKED", "no trucks"));
        clock.step(300);
        process(events.status(layout.crane(), "WORKING", null));

        assertEquals(
                List.of(
                        "I " + layout.crane() + " WORKING - 2026-10-30 08:00:00+00 2026-10-30 08:10:00+00",
                        "I " + layout.crane() + " BLOCKED no trucks 2026-10-30 08:10:00+00 2026-10-30 08:15:00+00",
                        "I " + layout.crane() + " WORKING - 2026-10-30 08:15:00+00 -"),
                lines(dataSource, "select * from (" + intervalsSql() + ") t"));
        var state = tx(() -> equipment.find(layout.port(), layout.crane())).orElseThrow();
        assertEquals(EquipmentStatus.WORKING, state.status());
        assertEquals(2, state.version());
    }

    @Test
    void positionAndAssignmentAreStoredNextToTheStatus() {
        process(events.status(layout.crane(), "WORKING", null));
        clock.step(5);
        process(events.position(layout.crane(), ProjectionTestSupport.LON, ProjectionTestSupport.LAT, 92.5, 2.5));
        clock.step(5);
        process(events.assignment(layout.crane(), "JOB-7", "MV-AURORA-0412"));

        var state = tx(() -> equipment.find(layout.port(), layout.crane())).orElseThrow();
        assertEquals(EquipmentStatus.WORKING, state.status());
        assertEquals(ProjectionTestSupport.LON, state.position().lon(), 1e-9);
        assertEquals(T0.plusSeconds(5), state.position().observedAt());
        assertEquals(Map.of("jobId", "JOB-7", "vesselCallId", "MV-AURORA-0412"), state.assignment());
        assertEquals(T0.plusSeconds(10), state.lastOccurredAt());
        assertEquals(3, count(dataSource, "select count(*) from ops.state_change"));
    }

    @Test
    void anOlderPositionAfterANewerOneLeavesTheNewerAndOpensExactlyOneStaleItem() {
        CanonicalEvent older =
                events.position(layout.crane(), ProjectionTestSupport.LON + 0.001, ProjectionTestSupport.LAT, null, 1);
        clock.step(30);
        CanonicalEvent newer =
                events.position(layout.crane(), ProjectionTestSupport.LON, ProjectionTestSupport.LAT, null, 1);

        process(newer);
        process(older);

        var state = tx(() -> equipment.find(layout.port(), layout.crane())).orElseThrow();
        assertEquals(ProjectionTestSupport.LON, state.position().lon(), 1e-9);
        assertEquals(T0.plusSeconds(30), state.position().observedAt());
        assertEquals(
                List.of("STALE_EVENT " + layout.crane() + " " + older.eventId()),
                lines(dataSource, "select kind || ' ' || entity_id || ' ' || event_id from ops.reconciliation_item"));
        // kept as evidence, announced to nobody
        assertEquals(2, count(dataSource, "select count(*) from ops.canonical_event"));
        assertEquals(1, count(dataSource, "select count(*) from ops.state_change"));
        assertEquals(0, state.version());
    }

    @Test
    void anOlderStatusIsInsertedIntoTheHistoryAtItsTrueTime() {
        process(events.status(layout.crane(), "WORKING", null));
        CanonicalEvent between = events.at(events.status(layout.crane(), "STARVED", null), T0.plusSeconds(60));
        clock.step(120);
        process(events.status(layout.crane(), "DOWN", null));

        process(between);

        assertEquals(
                List.of("WORKING 08:00:00 08:01:00", "STARVED 08:01:00 08:02:00", "DOWN 08:02:00 -"),
                lines(
                        dataSource,
                        "select status || ' ' || to_char(started_at at time zone 'UTC', 'HH24:MI:SS') || ' ' || "
                                + "coalesce(to_char(ended_at at time zone 'UTC', 'HH24:MI:SS'), '-') from ops.equipment_status_interval "
                                + "order by started_at"));
        assertEquals(
                EquipmentStatus.DOWN,
                tx(() -> equipment.find(layout.port(), layout.crane()))
                        .orElseThrow()
                        .status());
        assertEquals(1, count(dataSource, "select count(*) from ops.reconciliation_item"));
    }

    @Test
    void theRecordedChangeBodyIsExactlyTheSnapshotItemOfTheStoredRow() throws Exception {
        process(events.status(layout.crane(), "WORKING", "lifting"));
        clock.step(1);
        process(events.position(layout.crane(), 101.30663041234567, 2.95340579, 359.996, 2.555));
        clock.step(1);
        process(events.assignment(layout.crane(), "JOB-7", null));

        var stored = tx(() -> equipment.find(layout.port(), layout.crane())).orElseThrow();
        Map<String, Object> expected = json.convertValue(views.equipment(stored), OBJECT);
        var last = changes.after(layout.port(), 0, 10).get(2);

        assertEquals("equipment", last.entityType());
        assertEquals(layout.crane().toString(), last.entityId());
        assertEquals(stored.version(), last.entityVersion());
        assertEquals(
                json.readTree(json.writeValueAsString(expected)), json.readTree(json.writeValueAsString(last.body())));
    }

    @Test
    void aVesselCallIsUpsertedByCallCodeAndKeepsWhatLaterEventsDoNotCarry() {
        process(events.vesselCall("MV-AURORA-0412", "EXPECTED", layout.berth()));
        clock.step(1800);
        process(events.vesselCall("MV-AURORA-0412", "ALONGSIDE", null));

        var call = tx(() -> calls.find(layout.port(), "MV-AURORA-0412")).orElseThrow();
        assertEquals(VesselStatus.ALONGSIDE, call.status());
        assertEquals(layout.berth(), call.berthAssetId());
        assertEquals(1, call.version());
        assertEquals(1, count(dataSource, "select count(*) from ops.vessel_call"));
        var changesOfCall = changes.after(layout.port(), 0, 10);
        assertEquals(2, changesOfCall.size());
        assertEquals("vessel-call", changesOfCall.get(1).entityType());
        assertEquals(call.id().toString(), changesOfCall.get(1).entityId());
        assertEquals("ALONGSIDE", changesOfCall.get(1).body().get("status"));
    }

    @Test
    void theVesselCallChangeBodyIsExactlyTheSnapshotItemOfTheStoredRow() throws Exception {
        CanonicalEvent event = events.vesselCall("MV-AURORA-0412", "ALONGSIDE", layout.berth());
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) event.payload();
        payload.put("loaM", 189.94).put("beamM", 32.25).put("eta", "2026-10-30T14:00:00.123456789+08:00");
        process(events.withPayload(event, payload));

        var stored = tx(() -> calls.find(layout.port(), "MV-AURORA-0412")).orElseThrow();
        Map<String, Object> expected = json.convertValue(views.vesselCall(stored), OBJECT);
        var change = changes.after(layout.port(), 0, 10).get(0);

        assertEquals(
                json.readTree(json.writeValueAsString(expected)),
                json.readTree(json.writeValueAsString(change.body())));
    }

    @Test
    void anOlderVesselCallUpdateIsKeptAsEvidenceAndOpensOneStaleItem() {
        CanonicalEvent older = events.vesselCall("MV-AURORA-0412", "ALONGSIDE", null);
        clock.step(60);
        process(events.vesselCall("MV-AURORA-0412", "DEPARTED", null));

        process(older);

        assertEquals(
                VesselStatus.DEPARTED,
                tx(() -> calls.find(layout.port(), "MV-AURORA-0412"))
                        .orElseThrow()
                        .status());
        assertEquals(1, count(dataSource, "select count(*) from ops.reconciliation_item where kind = 'STALE_EVENT'"));
        assertEquals(1, count(dataSource, "select count(*) from ops.state_change"));
    }

    @Test
    void theSourceSequenceOrdersEventsOfOneSourceWhateverTheirClocks() {
        process(events.sequence(events.vesselCall("C1", "ALONGSIDE", null), 7L));
        clock.step(60);
        process(events.sequence(events.vesselCall("C1", "EXPECTED", null), 6L));

        assertEquals(
                VesselStatus.ALONGSIDE,
                tx(() -> calls.find(layout.port(), "C1")).orElseThrow().status());
        assertEquals(1, count(dataSource, "select count(*) from ops.reconciliation_item"));
        assertNull(tx(() -> calls.find(layout.port(), "C1")).orElseThrow().atd());
    }

    private static String intervalsSql() {
        return "select 'I ' || asset_id || ' ' || status || ' ' || coalesce(reason, '-') || ' ' || "
                + "to_char(started_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00 ' || "
                + "coalesce(to_char(ended_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00', '-') as line "
                + "from ops.equipment_status_interval order by started_at";
    }
}
