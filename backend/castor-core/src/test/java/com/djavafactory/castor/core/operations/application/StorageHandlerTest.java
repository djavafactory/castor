package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageHandlerTest {

    private final ProjectionFixture f = new ProjectionFixture();

    private Map<?, ?> lastInventory() {
        for (int i = f.changes.all.size() - 1; i >= 0; i--) {
            if (f.changes.all.get(i).entityType().equals("inventory")) {
                return f.changes.all.get(i).body();
            }
        }
        throw new AssertionError("no inventory change");
    }

    private void stock(String commodity, String family, String unit, double quantity) {
        String lot = "itap:" + commodity;
        f.handle(f.lotRegistered, f.events.lotRegistered(lot, family, commodity, unit, quantity, null));
        f.clock.step(10);
        f.handle(f.lotMoved, f.events.lotMoved(lot, f.berth, f.tank, quantity, unit));
    }

    @Test
    void aTankLevelWithoutADensityHasAnUnknownMass() {
        f.handle(f.level, f.events.storageLevel(f.tank, "EDOIL", 3120.75, "CUBIC_METRES", null, null));

        Map<?, ?> dto = lastInventory();
        assertEquals("UNKNOWN", ((Map<?, ?>) dto.get("observedMass")).get("status"));
        assertNull(((Map<?, ?>) dto.get("observedMass")).get("tonnes"));
        assertEquals("UNKNOWN", ((Map<?, ?>) dto.get("bookMass")).get("status"));
        // the level is kept next to a book balance that is unknown, not zero
        assertNull(dto.get("quantity"));
        assertEquals(3120.75, ((Number) ((Map<?, ?>) dto.get("observed")).get("quantity")).doubleValue());
    }

    @Test
    void aTankLevelWithADensityHasAMassInTonnes() {
        f.handle(f.level, f.events.storageLevel(f.tank, "EDOIL", 1000, "CUBIC_METRES", 891.2, 48.5));

        Map<?, ?> mass = (Map<?, ?>) lastInventory().get("observedMass");
        assertEquals("KNOWN", mass.get("status"));
        assertEquals(
                0,
                new BigDecimal("891.200")
                        .compareTo(new BigDecimal(mass.get("tonnes").toString())));
        assertEquals(48.5, ((Number) ((Map<?, ?>) lastInventory().get("observed")).get("temperatureC")).doubleValue());
    }

    @Test
    void aLevelWithinTwoPercentOfTheBookIsNoConflictAndOneBeyondIsOne() {
        stock("OIL", "LIQUID_BULK", "CUBIC_METRES", 1000);
        f.clock.step(10);
        f.handle(f.level, f.events.storageLevel(f.tank, "OIL", 1019, "CUBIC_METRES", null, null));
        assertTrue(f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).isEmpty());

        f.clock.step(10);
        f.handle(f.level, f.events.storageLevel(f.tank, "OIL", 1021, "CUBIC_METRES", null, null));
        var conflicts = f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT);
        assertEquals(1, conflicts.size());
        assertEquals("level", conflicts.get(0).detail().get("field"));
        assertEquals(new BigDecimal("2.10"), conflicts.get(0).detail().get("differencePercent"));

        // the book balance is not touched by a measurement
        assertEquals(new BigDecimal("1000.000"), f.inventories.book(f.tank, "OIL"));
    }

    @Test
    void aConflictThatIsStillOpenIsNotOpenedAgainByTheNextReading() {
        stock("OIL", "LIQUID_BULK", "CUBIC_METRES", 1000);
        for (int i = 0; i < 3; i++) {
            f.clock.step(10);
            f.handle(f.level, f.events.storageLevel(f.tank, "OIL", 900, "CUBIC_METRES", null, null));
        }
        assertEquals(1, f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).size());
    }

    @Test
    void aVolumeAndATonnageMeetOnlyThroughADensity() {
        // the book is counted in tonnes, the sensor reads cubic metres
        f.handle(f.lotRegistered, f.events.lotRegistered("itap:OIL", "DRY_BULK", "OIL", "TONNES", 800, null));
        f.handle(f.lotMoved, f.events.lotMoved("itap:OIL", f.berth, f.tank, 800, "TONNES"));
        f.clock.step(10);
        f.handle(f.level, f.events.storageLevel(f.tank, "OIL", 1000, "CUBIC_METRES", null, null));
        assertTrue(f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).isEmpty(), "no density: nothing to compare");

        f.clock.step(10);
        f.handle(f.level, f.events.storageLevel(f.tank, "OIL", 1000, "CUBIC_METRES", 850.0, null));
        // 1000 m3 x 850 kg/m3 = 850 t against 800 t booked
        assertEquals(1, f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).size());
    }

    @Test
    void aReadingOlderThanTheOneHeldChangesNothingAndOpensAStaleItem() {
        f.clock.step(600);
        f.handle(f.level, f.events.storageLevel(f.tank, "OIL", 500, "CUBIC_METRES", null, null));
        var older = f.events.at(
                f.events.storageLevel(f.tank, "OIL", 100, "CUBIC_METRES", null, null), ProjectionFixture.T0);
        f.handle(f.level, older);

        assertEquals(
                0,
                new BigDecimal("500")
                        .compareTo(f.inventories
                                .find(1, f.tank, "OIL")
                                .orElseThrow()
                                .level()
                                .quantity()
                                .value()));
        assertEquals(1, f.reconciliation.of(ReconciliationKind.STALE_EVENT).size());
    }

    @Test
    void aSensorOnSomethingThatIsNotAStorageAreaIsUnknown() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.level, f.events.storageLevel(f.crane, "OIL", 1, "CUBIC_METRES", null, null)));
        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
    }

    @Test
    void anAdjustmentIsAnExplicitRowAndChangesTheBalanceBySignedDelta() {
        stock("COAL", "DRY_BULK", "TONNES", 100);
        f.clock.step(10);
        f.handle(f.adjusted, f.events.inventoryAdjusted(f.tank, null, -7.5, "TONNES", "stock count"));

        assertEquals(new BigDecimal("92.500"), f.inventories.book(f.tank, "COAL"));
        assertEquals(1, f.inventories.adjustments.size());
        var adjustment = f.inventories.adjustments.get(0);
        assertEquals("stock count", adjustment.reason());
        assertEquals(new BigDecimal("-7.500"), adjustment.delta().value());
        assertEquals("inventory", f.changes.all.get(f.changes.all.size() - 1).entityType());
    }

    @Test
    void anAdjustmentSettlesAFlaggedRow() {
        f.handle(f.lotRegistered, f.events.lotRegistered("itap:C", "DRY_BULK", "COAL", "TONNES", 10, null));
        f.handle(f.lotMoved, f.events.lotMoved("itap:C", f.tank, f.berth, 10, "TONNES"));
        assertTrue(f.inventories.find(1, f.tank, "COAL").orElseThrow().needsReconciliation());

        f.clock.step(10);
        f.handle(f.adjusted, f.events.inventoryAdjusted(f.tank, "COAL", 10, "TONNES", "opening stock"));

        var row = f.inventories.find(1, f.tank, "COAL").orElseThrow();
        assertFalse(row.needsReconciliation());
        assertEquals(new BigDecimal("10.000"), row.quantity().value());
    }

    @Test
    void anAdjustmentThatWouldGoBelowZeroIsRefused() {
        stock("COAL", "DRY_BULK", "TONNES", 5);
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.adjusted, f.events.inventoryAdjusted(f.tank, "COAL", -6, "TONNES", "typo")));

        assertEquals(DeadLetterReason.INVALID_EVENT, e.reason());
        assertEquals(new BigDecimal("5.000"), f.inventories.book(f.tank, "COAL"));
    }

    @Test
    void anAdjustmentInAnotherUnitIsAMismatch() {
        stock("COAL", "DRY_BULK", "TONNES", 5);
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.adjusted, f.events.inventoryAdjusted(f.tank, "COAL", 1, "PIECES", "x")));
        assertEquals(DeadLetterReason.UNIT_MISMATCH, e.reason());
    }

    @Test
    void anAdjustmentWithoutACommodityNeedsExactlyOneToMeanIt() {
        stock("COAL", "DRY_BULK", "TONNES", 5);
        f.handle(f.adjusted, f.events.inventoryAdjusted(f.tank, null, 1, "TONNES", "count"));
        assertEquals(new BigDecimal("6.000"), f.inventories.book(f.tank, "COAL"));

        stock("IRON", "DRY_BULK", "TONNES", 5);
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.adjusted, f.events.inventoryAdjusted(f.tank, null, 1, "TONNES", "count")));
        assertEquals(DeadLetterReason.INVALID_EVENT, e.reason());
    }
}
