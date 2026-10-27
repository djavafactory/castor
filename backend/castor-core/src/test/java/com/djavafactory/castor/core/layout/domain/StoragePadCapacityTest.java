package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The capacity rules of CORE-04 part 2: a 62 x 34 m slot holds about 7,560 t of coal and 12,355 t of wheat. */
class StoragePadCapacityTest {

    private static final StoragePadCapacity.Cargo COAL = new StoragePadCapacity.Cargo("PILE", 0.85, null, 37.0);
    private static final StoragePadCapacity.Cargo WHEAT = new StoragePadCapacity.Cargo("SILO", 0.77, null, null);
    private static final StoragePadCapacity.Cargo STEEL = new StoragePadCapacity.Cargo("STACK", 3.0, 5.0, null);

    @Test
    void coalSlotHoldsAbout7560Tonnes() {
        assertEquals(7560, StoragePadCapacity.slotTonnes(62, 34, 10, COAL), 1.0);
    }

    @Test
    void wheatSiloSlotHoldsAbout12355Tonnes() {
        assertEquals(12355, StoragePadCapacity.slotTonnes(62, 34, 10, WHEAT), 1.0);
    }

    @Test
    void stackIsLimitedByTheCargoMaxStack() {
        // max height 10 m but steel stacks 5 m: K(0.92,0.92) x 58.28 x 31.96 x 5 x 3.0
        double expected = StoragePadCapacity.k(0.92, 0.92) * 58.28 * 31.96 * 5 * 3.0;
        assertEquals(expected, StoragePadCapacity.slotTonnes(62, 34, 10, STEEL), 0.01);
    }

    @Test
    void cargoStoredElsewhereHasNoPadCapacity() {
        var tank = new StoragePadCapacity.Cargo("TANK", 0.85, null, null);
        assertEquals(0, StoragePadCapacity.slotTonnes(62, 34, 10, tank));
    }

    @Test
    void padIsBaysTimesRowsSlots() {
        var pad = new AssetDetails.StoragePad(2, 1, BigDecimal.valueOf(62), BigDecimal.valueOf(34), BigDecimal.TEN);
        assertEquals(
                2 * StoragePadCapacity.slotTonnes(62, 34, 10, COAL), StoragePadCapacity.padTonnes(pad, COAL), 0.001);
        assertEquals(124, StoragePadCapacity.footprint(pad)[0]);
        assertEquals(34, StoragePadCapacity.footprint(pad)[1]);
    }

    @Test
    void zoneCodesCountBaysFromOneWithTwoDigits() {
        assertEquals(List.of("PAD-A1-01-1", "PAD-A1-02-1"), StoragePadCapacity.zoneCodes("PAD-A1", 2, 1));
        assertEquals(4, StoragePadCapacity.zoneCodes("PAD-R1", 2, 2).size());
    }
}
