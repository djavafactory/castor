package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.MassBasis;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CargoLotHandlerTest {

    private static final String LOT = "itap:COIL-1";

    private final ProjectionFixture f = new ProjectionFixture();

    private void register(String lot, String unit) {
        f.handle(f.lotRegistered, f.events.lotRegistered(lot, "STEEL", "COIL", unit, 60, null));
    }

    private void move(Object from, Object to, double quantity) {
        f.handle(f.lotMoved, f.events.lotMoved(LOT, from, to, quantity, "PIECES"));
    }

    private BigDecimal book(java.util.UUID storage) {
        return f.inventories.book(storage, "COIL");
    }

    @Test
    void registeringALotStoresItWithoutALocation() {
        register(LOT, "PIECES");

        var lot = f.cargo.lots.get(LOT);
        assertEquals("STEEL", lot.cargoFamily());
        assertEquals(new BigDecimal("60.000"), lot.registeredQuantity());
        assertNull(lot.currentLocationId());
        assertNull(lot.locationOccurredAt());
        var change = f.changes.all.get(0);
        assertEquals("cargo-lot", change.entityType());
        assertEquals(lot.id().toString(), change.entityId());
    }

    @Test
    void aFamilyThatIsNotInTheReferenceDataIsUnknown() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.lotRegistered, f.events.lotRegistered(LOT, "NO_SUCH", "COIL", "PIECES", 1, null)));

        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
        assertTrue(f.cargo.lots.isEmpty());
    }

    @Test
    void registeringTheSameLotAgainWithNothingNewChangesNothing() {
        register(LOT, "PIECES");
        f.clock.step(60);
        register(LOT, "PIECES");

        assertEquals(1, f.changes.all.size());
        assertEquals(0, f.cargo.lots.get(LOT).version());
    }

    @Test
    void registeringALotInAnotherUnitIsAMismatch() {
        register(LOT, "PIECES");
        var e = assertThrows(RejectedEventException.class, () -> register(LOT, "TONNES"));
        assertEquals(DeadLetterReason.UNIT_MISMATCH, e.reason());
    }

    @Test
    void aMovementMovesTheBalanceAndTheLocation() {
        register(LOT, "PIECES");
        f.clock.step(60);
        move(f.berth, f.yard, 5);

        assertEquals(new BigDecimal("5.000"), book(f.yard));
        assertEquals(f.yard, f.cargo.lots.get(LOT).currentLocationId());
        assertEquals(1, f.cargo.movements.size());
        // the berth is not a storage area: it has no balance
        assertTrue(f.inventories.findByStorage(1, f.berth).isEmpty());
        assertEquals("inventory", f.changes.all.get(f.changes.all.size() - 2).entityType());
        assertEquals("cargo-lot", f.changes.all.get(f.changes.all.size() - 1).entityType());
    }

    @Test
    void thirtyFiveCoilsInAndTwentyFiveOutOfABufferLeaveTen() {
        register(LOT, "PIECES");
        f.clock.step(60);
        move(f.berth, f.yard, 20);
        for (int i = 0; i < 3; i++) {
            f.clock.step(600);
            move(f.berth, f.yard, 5);
        }
        for (int i = 0; i < 5; i++) {
            f.clock.step(600);
            move(f.yard, f.berth, 5);
        }

        assertEquals(new BigDecimal("10.000"), book(f.yard));
        assertEquals(9, f.cargo.movements.size());
        assertTrue(f.reconciliation.of(ReconciliationKind.NEGATIVE_BALANCE).isEmpty());
    }

    @Test
    void theSameMovementTwiceIsCountedOnce() {
        register(LOT, "PIECES");
        f.clock.step(60);
        CanonicalEvent once = f.events.lotMoved(LOT, f.berth, f.yard, 5, "PIECES");
        f.handle(f.lotMoved, once);
        f.handle(f.lotMoved, once);

        assertEquals(new BigDecimal("5.000"), book(f.yard));
        assertEquals(1, f.cargo.movements.size());
    }

    @Test
    void anOlderMovementKeepsTheBalancesRightLeavesTheLocationAndOpensOneStaleItem() {
        register(LOT, "PIECES");
        f.clock.step(3600);
        move(f.berth, f.yard, 10);
        var older = f.events.at(
                f.events.lotMoved(LOT, f.berth, f.buffer, 4, "PIECES"), ProjectionFixture.T0.plusSeconds(60));
        f.handle(f.lotMoved, older);

        assertEquals(new BigDecimal("10.000"), book(f.yard));
        assertEquals(new BigDecimal("4.000"), f.inventories.book(f.buffer, "COIL"));
        assertEquals(f.yard, f.cargo.lots.get(LOT).currentLocationId());
        var stale = f.reconciliation.of(ReconciliationKind.STALE_EVENT);
        assertEquals(1, stale.size());
        assertEquals(f.cargo.lots.get(LOT).id().toString(), stale.get(0).entityId());
        assertEquals(2, f.cargo.movements.size());
    }

    @Test
    void aMovementInAnotherUnitThanTheLotIsDeadLetteredAndChangesNothing() {
        register(LOT, "PIECES");
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.lotMoved, f.events.lotMoved(LOT, f.berth, f.yard, 5, "TONNES")));

        assertEquals(DeadLetterReason.UNIT_MISMATCH, e.reason());
        assertTrue(f.cargo.movements.isEmpty());
        assertTrue(f.inventories.rows.isEmpty());
    }

    @Test
    void aMovementOfALotThatIsNotRegisteredIsUnknown() {
        var e = assertThrows(RejectedEventException.class, () -> move(f.berth, f.yard, 5));
        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
    }

    @Test
    void aMovementThatWouldGoBelowZeroIsKeptTheBalanceStaysAndTheRowIsFlagged() {
        register(LOT, "PIECES");
        f.clock.step(60);
        move(f.berth, f.yard, 10);
        f.clock.step(60);
        move(f.yard, f.berth, 15);

        assertEquals(new BigDecimal("10.000"), book(f.yard));
        assertTrue(f.inventories.rows.values().stream().anyMatch(r -> r.needsReconciliation()));
        assertEquals(2, f.cargo.movements.size());
        var items = f.reconciliation.of(ReconciliationKind.NEGATIVE_BALANCE);
        assertEquals(1, items.size());
        assertEquals(f.yard + ":COIL", items.get(0).entityId());
        assertEquals(new BigDecimal("10.000"), items.get(0).detail().get("balance"));
        assertEquals(new BigDecimal("-15.000"), items.get(0).detail().get("delta"));
    }

    @Test
    void aMovementOutOfAnAreaWithNoStockFlagsItAtZero() {
        register(LOT, "PIECES");
        move(f.yard, f.berth, 3);

        assertEquals(BigDecimal.ZERO.setScale(3), book(f.yard));
        assertTrue(f.inventories.find(1, f.yard, "COIL").orElseThrow().needsReconciliation());
        assertEquals(1, f.reconciliation.of(ReconciliationKind.NEGATIVE_BALANCE).size());
    }

    @Test
    void aBalanceAboveTheUsableCapacityIsKeptAsReportedAndOpensAnItem() {
        f.handle(f.lotRegistered, f.events.lotRegistered(LOT, "DRY_BULK", "COAL", "TONNES", 500, "DRY"));
        f.clock.step(60);
        f.handle(f.lotMoved, f.events.lotMoved(LOT, f.berth, f.buffer, 120, "TONNES"));

        assertEquals(new BigDecimal("120.000"), f.inventories.book(f.buffer, "COAL"));
        var items = f.reconciliation.of(ReconciliationKind.CAPACITY_EXCEEDED);
        assertEquals(1, items.size());
        assertEquals(new BigDecimal("120.000"), items.get(0).detail().get("stock"));
        assertEquals(new BigDecimal("100"), items.get(0).detail().get("capacity"));
    }

    @Test
    void anAreaWithNoCapacityInTheLayoutIsNeverOverCapacity() {
        register(LOT, "PIECES");
        move(f.berth, f.yard, 1_000_000);

        assertTrue(f.reconciliation.of(ReconciliationKind.CAPACITY_EXCEEDED).isEmpty());
        var dto = f.changes.all.get(1).body();
        assertEquals("UNKNOWN", ((java.util.Map<?, ?>) dto.get("capacity")).get("status"));
    }

    @Test
    void wetAndDryTonnesAreNeverAddedTogether() {
        f.handle(f.lotRegistered, f.events.lotRegistered("itap:WET", "DRY_BULK", "COAL", "TONNES", 50, "WET"));
        f.handle(f.lotRegistered, f.events.lotRegistered("itap:DRY", "DRY_BULK", "COAL", "TONNES", 50, "DRY"));
        f.handle(f.lotMoved, f.events.lotMoved("itap:WET", f.berth, f.pad, 10, "TONNES"));

        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.lotMoved, f.events.lotMoved("itap:DRY", f.berth, f.pad, 10, "TONNES")));

        assertEquals(DeadLetterReason.UNIT_MISMATCH, e.reason());
        assertEquals(new BigDecimal("10.000"), f.inventories.book(f.pad, "COAL"));
        assertEquals(
                MassBasis.WET,
                f.inventories.find(1, f.pad, "COAL").orElseThrow().massBasis());
    }
}
