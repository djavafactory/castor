package com.djavafactory.castor.core.operations.application;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentStatusHandlerTest {

    private final ProjectionFixture f = new ProjectionFixture();

    private CanonicalEvent status(String status, String reason) {
        return f.events.status(f.crane, status, reason);
    }

    @Test
    void theFirstStatusCreatesTheRowAndOpensAnInterval() {
        f.handle(f.status, status("WORKING", null));

        var state = f.equipment.states.get(f.crane);
        assertEquals(EquipmentStatus.WORKING, state.status());
        assertEquals("SENSORS", state.provenance().source());
        assertEquals(ProjectionFixture.T0, state.provenance().observedAt());
        assertEquals(ProjectionFixture.T0, state.lastOccurredAt());
        assertEquals(List.of("WORKING 0..open"), f.equipment.history(f.crane));
    }

    @Test
    void aChangeClosesTheOpenIntervalAndOpensTheNext() {
        f.handle(f.status, status("WORKING", null));
        f.clock.step(60);
        f.handle(f.status, status("DOWN", "hydraulic leak"));

        var state = f.equipment.states.get(f.crane);
        assertEquals(EquipmentStatus.DOWN, state.status());
        assertEquals("hydraulic leak", state.statusReason());
        assertEquals(1, state.version());
        assertEquals(List.of("WORKING 0..60", "DOWN 60..open"), f.equipment.history(f.crane));
    }

    @Test
    void theSameStatusAgainKeepsTheIntervalButRefreshesTheObservation() {
        f.handle(f.status, status("IDLE", null));
        f.clock.step(30);
        f.handle(f.status, status("IDLE", null));

        assertEquals(List.of("IDLE 0..open"), f.equipment.history(f.crane));
        assertEquals(
                ProjectionFixture.T0.plusSeconds(30),
                f.equipment.states.get(f.crane).provenance().observedAt());
        assertEquals(2, f.changes.all.size());
    }

    @Test
    void everyChangeIsRecordedWithTheDtoAsItsBody() {
        f.handle(f.status, status("WORKING", null));

        var change = f.changes.all.get(0);
        assertEquals("equipment", change.entityType());
        assertEquals(f.crane.toString(), change.entityId());
        assertEquals(0, change.entityVersion());
        assertEquals("WORKING", change.body().get("status"));
        assertEquals(f.crane.toString(), change.body().get("id"));
        assertEquals("SIMULATED", change.body().get("quality"));
        assertEquals("SENSORS", change.body().get("source"));
        assertEquals(ProjectionFixture.T0.toString(), change.body().get("observedAt"));
    }

    @Test
    void anAssetOutsideThePublishedLayoutIsUnknown() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.status, f.events.status(java.util.UUID.randomUUID(), "IDLE", null)));
        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
        assertTrue(f.equipment.states.isEmpty());
    }

    @Test
    void anIdThatIsNotAnAssetIdIsUnknown() {
        var e = assertThrows(
                RejectedEventException.class, () -> f.handle(f.status, f.events.status("loader-07", "IDLE", null)));
        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
    }

    @Test
    void aStatusOutsideTheCatalogueIsInvalid() {
        var e = assertThrows(RejectedEventException.class, () -> f.handle(f.status, status("DANCING", null)));
        assertEquals(DeadLetterReason.INVALID_EVENT, e.reason());
    }

    @Test
    void anOlderStatusLeavesTheRowAloneButJoinsTheHistoryAtItsTrueTime() {
        f.handle(f.status, status("WORKING", null));
        f.clock.step(20);
        f.handle(f.status, status("DOWN", null));
        var late = f.events.at(status("IDLE", "lunch"), ProjectionFixture.T0.plusSeconds(10));

        f.handle(f.status, late);

        var state = f.equipment.states.get(f.crane);
        assertEquals(EquipmentStatus.DOWN, state.status());
        assertEquals(ProjectionFixture.T0.plusSeconds(20), state.lastOccurredAt());
        assertEquals(List.of("WORKING 0..10", "IDLE 10..20", "DOWN 20..open"), f.equipment.history(f.crane));
        assertEquals(2, f.changes.all.size(), "a stale event announces nothing");
        assertEquals(1, f.reconciliation.items.size());
        assertEquals(
                ReconciliationKind.STALE_EVENT, f.reconciliation.items.get(0).kind());
        assertEquals(late.eventId(), f.reconciliation.items.get(0).eventId().toString());
    }

    @Test
    void anOlderStatusInsideTheOpenIntervalDoesNotReplaceTheCurrentOne() {
        f.handle(f.status, status("WORKING", null));
        f.clock.step(30);
        f.handle(f.position, f.events.position(f.crane, ProjectionFixture.LON, ProjectionFixture.LAT, null, 2.5));

        f.handle(f.status, f.events.at(status("IDLE", null), ProjectionFixture.T0.plusSeconds(10)));

        assertEquals(EquipmentStatus.WORKING, f.equipment.states.get(f.crane).status());
        assertEquals(List.of("WORKING 0..open"), f.equipment.history(f.crane));
        assertEquals(1, f.reconciliation.items.size());
    }

    @Test
    void fromOneSourceTheSequenceDecidesNotTheClock() {
        f.handle(f.status, f.events.sequence(status("WORKING", null), 10L));
        f.clock.step(60);
        // later by the clock but a lower sequence from the same source: older
        f.handle(f.status, f.events.sequence(status("DOWN", null), 9L));

        assertEquals(EquipmentStatus.WORKING, f.equipment.states.get(f.crane).status());
        assertEquals(1, f.reconciliation.items.size());
    }

    @Test
    void aLowerSequenceFromAnotherSourceIsJudgedByTheClock() {
        f.handle(f.status, f.events.sequence(status("WORKING", null), 10L));
        f.clock.step(60);
        f.handle(f.status, f.events.sequence(f.events.source(status("DOWN", null), "CBAS"), 3L));

        assertEquals(EquipmentStatus.DOWN, f.equipment.states.get(f.crane).status());
        assertTrue(f.reconciliation.items.isEmpty());
    }
}
