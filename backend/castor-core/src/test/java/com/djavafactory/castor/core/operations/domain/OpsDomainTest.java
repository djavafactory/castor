package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.contracts.UnitMismatchException;
import com.djavafactory.castor.core.shared.domain.ClockInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpsDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");
    private static final UUID PAD = UUID.randomUUID();

    private static Quantity tonnes(String value) {
        return new Quantity(new BigDecimal(value), Unit.TONNES);
    }

    private static StorageInventory stock(String tonnes) {
        return new StorageInventory(1, PAD, "COAL", tonnes(tonnes), NOW, "SCSS", Quality.OBSERVED, 3);
    }

    @Test
    void quantityRefusesArithmeticAcrossUnits() {
        assertThrows(UnitMismatchException.class, () -> tonnes("1").plus(new Quantity(BigDecimal.ONE, Unit.PIECES)));
        assertEquals(0, tonnes("5").minus(tonnes("2")).value().compareTo(new BigDecimal("3")));
    }

    @Test
    void freshnessFollowsTheAgeOfTheObservation() {
        Duration fresh = Duration.ofSeconds(30);
        Duration stale = Duration.ofMinutes(5);
        assertEquals(Freshness.FRESH, Freshness.of(NOW.minusSeconds(10), NOW, fresh, stale));
        assertEquals(Freshness.FRESH, Freshness.of(NOW.plusSeconds(10), NOW, fresh, stale));
        assertEquals(Freshness.AGING, Freshness.of(NOW.minusSeconds(120), NOW, fresh, stale));
        assertEquals(Freshness.STALE, Freshness.of(NOW.minusSeconds(301), NOW, fresh, stale));
    }

    @Test
    void neverObservedIsUnknownNotFresh() {
        assertEquals(Freshness.UNKNOWN, Freshness.of(null, NOW, Duration.ofSeconds(30), Duration.ofMinutes(5)));
    }

    @Test
    void freshnessWindowsMustBeOrdered() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Freshness.of(NOW, NOW, Duration.ofMinutes(5), Duration.ofSeconds(1)));
    }

    @Test
    void provenanceNeedsSourceQualityAndTime() {
        assertThrows(IllegalArgumentException.class, () -> new Provenance(" ", Quality.OBSERVED, NOW));
        assertThrows(NullPointerException.class, () -> new Provenance("SCSS", null, NOW));
        assertThrows(NullPointerException.class, () -> new Provenance("SCSS", Quality.OBSERVED, null));
    }

    @Test
    void adjustmentMovesTheStockAndKeepsTheVersionForTheRepository() {
        StorageInventory next = stock("100").adjustedBy(tonnes("-40"), NOW.plusSeconds(1), "EDIC", Quality.INFERRED);
        assertEquals(0, next.quantity().value().compareTo(new BigDecimal("60")));
        assertEquals(3, next.version());
        assertEquals("EDIC", next.source());
        assertEquals(Quality.INFERRED, next.quality());
    }

    @Test
    void adjustmentBelowZeroIsRefused() {
        assertThrows(
                NegativeBalanceException.class,
                () -> stock("10").adjustedBy(tonnes("-10.001"), NOW, "x", Quality.OBSERVED));
        assertEquals(
                0,
                stock("10")
                        .adjustedBy(tonnes("-10"), NOW, "x", Quality.OBSERVED)
                        .quantity()
                        .value()
                        .signum());
    }

    @Test
    void adjustmentInAnotherUnitIsRefused() {
        var litres = new Quantity(BigDecimal.ONE, Unit.CUBIC_METRES);
        assertThrows(UnitMismatchException.class, () -> stock("10").adjustedBy(litres, NOW, "x", Quality.OBSERVED));
    }

    @Test
    void negativeInventoryCannotBeBuilt() {
        assertThrows(NegativeBalanceException.class, () -> stock("-1"));
    }

    @Test
    void movementMovesAPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> movement("0"));
        assertThrows(IllegalArgumentException.class, () -> movement("-3"));
        movement("3");
    }

    private static CargoMovement movement(String tonnes) {
        return new CargoMovement(1, UUID.randomUUID(), null, PAD, tonnes(tonnes), null, NOW, UUID.randomUUID());
    }

    @Test
    void positionMustBeWgs84AndObserved() {
        assertThrows(IllegalArgumentException.class, () -> new Position(181, 0, null, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, -91, null, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Position(101.3, 2.9, null, null, null));
    }

    @Test
    void cargoFamilyIsDataWithADefaultUnit() {
        var family = new CargoFamily("CONTAINER", "Container", Unit.PIECES);
        assertEquals(Unit.PIECES, family.defaultUnit());
        assertThrows(IllegalArgumentException.class, () -> new CargoFamily("", "x", Unit.TONNES));
    }

    @Test
    void systemClockRunsAtSpeedOne() {
        assertEquals(1, ClockInfo.SYSTEM.speed());
        assertSame(ClockInfo.SYSTEM, ClockInfo.SYSTEM);
        assertThrows(IllegalArgumentException.class, () -> new ClockInfo(0));
    }
}
