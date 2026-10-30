package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentPositionHandlerTest {

    private final ProjectionFixture f = new ProjectionFixture();

    @Test
    void storesPositionHeadingAccuracyAndTheTimeOfTheFix() {
        f.handle(f.position, f.events.position(f.crane, 101.3012, 2.9871, 92.5, 2.5));

        var state = f.equipment.states.get(f.crane);
        assertEquals(101.3012, state.position().lon());
        assertEquals(2.9871, state.position().lat());
        assertEquals(new BigDecimal("92.50"), state.position().headingDeg());
        assertEquals(new BigDecimal("2.50"), state.position().accuracyM());
        assertEquals(ProjectionFixture.T0, state.position().observedAt());
        assertEquals("SIMULATED", state.provenance().quality().name());
    }

    @Test
    void aPositionBeforeAnyStatusLeavesTheStatusUnknown() {
        f.handle(f.position, f.events.position(f.crane, ProjectionFixture.LON, ProjectionFixture.LAT, null, 1));

        var state = f.equipment.states.get(f.crane);
        assertNull(state.status());
        assertNull(state.position().headingDeg());
        assertTrue(f.equipment.intervals.isEmpty(), "no status, so no interval");
        assertNull(f.changes.all.get(0).body().get("status"));
    }

    @Test
    void aPositionKeepsTheStatusItFinds() {
        f.handle(f.status, f.events.status(f.crane, "WORKING", "lifting"));
        f.clock.step(5);
        f.handle(f.position, f.events.position(f.crane, ProjectionFixture.LON, ProjectionFixture.LAT, 10.0, 1));

        var state = f.equipment.states.get(f.crane);
        assertEquals(EquipmentStatus.WORKING, state.status());
        assertEquals("lifting", state.statusReason());
        assertEquals(1, f.equipment.intervals.size());
    }

    @Test
    void aPositionOutsideThePortExtentIsRejectedAndNothingIsStored() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.position, f.events.position(f.crane, 0.0, 0.0, null, 1)));

        assertEquals(DeadLetterReason.OUT_OF_EXTENT, e.reason());
        assertTrue(f.equipment.states.isEmpty());
        assertTrue(f.changes.all.isEmpty());
    }

    @Test
    void anOlderPositionAfterANewerOneLeavesTheNewerAndOpensOneStaleItem() {
        var newer = f.events.position(f.crane, 101.31, 2.99, null, 1);
        f.clock.step(-30);
        var older = f.events.position(f.crane, 101.32, 2.98, null, 1);
        f.handle(f.position, newer);

        f.handle(f.position, older);

        var state = f.equipment.states.get(f.crane);
        assertEquals(101.31, state.position().lon());
        assertEquals(1, f.reconciliation.items.size());
        assertEquals("STALE_EVENT", f.reconciliation.items.get(0).kind().name());
        assertEquals(1, f.changes.all.size());
    }

    @Test
    void aHeadingThatRoundsToAFullTurnWrapsToZero() {
        f.handle(f.position, f.events.position(f.crane, ProjectionFixture.LON, ProjectionFixture.LAT, 359.999, 1));

        assertEquals(
                new BigDecimal("0.00"),
                f.equipment.states.get(f.crane).position().headingDeg());
    }

    @Test
    void theBodyShowsThePositionAsTheColumnsKeepIt() {
        f.handle(f.position, f.events.position(f.crane, ProjectionFixture.LON, ProjectionFixture.LAT, 12.345, 2.555));

        @SuppressWarnings("unchecked")
        var position =
                (java.util.Map<String, Object>) f.changes.all.get(0).body().get("position");
        assertEquals(
                0,
                new BigDecimal("12.35")
                        .compareTo(new BigDecimal(position.get("headingDeg").toString())));
        assertEquals(
                0,
                new BigDecimal("2.56")
                        .compareTo(new BigDecimal(position.get("accuracyM").toString())));
    }
}
