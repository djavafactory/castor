package com.djavafactory.castor.gateway.mapping.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.gateway.mapping.domain.Amounts.Amount;
import com.djavafactory.castor.gateway.support.AdapterFixtures.InMemoryMappings;
import com.djavafactory.castor.gateway.support.TestClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingDomainTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Instant TEN = Instant.parse("2026-10-29T10:00:00Z");

    private static String reason(MappingThrower thrower) {
        return assertThrows(MappingException.class, thrower::run).reasonCode();
    }

    private interface MappingThrower {
        void run() throws MappingException;
    }

    private static JsonNode number(String text) {
        return JSON.numberNode(new BigDecimal(text));
    }

    // --- unit codes ---

    @Test
    void theTableOfSourceCodesGivesCanonicalUnits() throws MappingException {
        assertEquals(Unit.TONNES, UnitCodes.amount("t"));
        assertEquals(Unit.TONNES, UnitCodes.amount("MT"));
        assertEquals(Unit.PIECES, UnitCodes.amount("pcs"));
        assertEquals(Unit.CUBIC_METRES, UnitCodes.amount("m3"));
        assertEquals(Unit.VEHICLES, UnitCodes.amount("units"));
        assertEquals(Unit.TONNES, UnitCodes.amount("TONNES"), "canonical names are accepted");
        assertEquals(Unit.TONNES_PER_HOUR, UnitCodes.rate("t/h"));
        assertEquals(Unit.VEHICLES_PER_HOUR, UnitCodes.rate("units/h"));
        assertEquals(Unit.CUBIC_METRES_PER_HOUR, UnitCodes.rate("m3/h"));
        assertEquals(Unit.PIECES_PER_HOUR, UnitCodes.rate("pcs/h"));
    }

    @Test
    void anythingElseIsMalformedAndNeverConverted() {
        assertEquals("MALFORMED_UNIT", reason(() -> UnitCodes.amount("kg")));
        assertEquals("MALFORMED_UNIT", reason(() -> UnitCodes.amount("mt")));
        assertEquals("MALFORMED_UNIT", reason(() -> UnitCodes.amount("")));
        assertEquals("MALFORMED_UNIT", reason(() -> UnitCodes.amount(null)));
        assertEquals("MALFORMED_UNIT", reason(() -> UnitCodes.amount("t/h")), "a rate is not an amount");
        assertEquals("MALFORMED_UNIT", reason(() -> UnitCodes.rate("t")), "an amount is not a rate");
        assertEquals("MALFORMED_UNIT", reason(() -> UnitCodes.rate("kg/h")));
    }

    @Test
    void aQuantityIsANumberWithOneUnit() throws MappingException {
        assertEquals(
                new Amount(new BigDecimal("12.50"), Unit.TONNES), Amounts.amount(number("12.50"), JSON.textNode("t")));
        assertEquals(new Amount(new BigDecimal("12.5"), Unit.TONNES), Amounts.amount(JSON.textNode("12.5 MT"), null));
        assertEquals(new Amount(BigDecimal.ZERO, Unit.PIECES), Amounts.amount(JSON.textNode("0pcs"), JSON.nullNode()));
        assertEquals(
                new Amount(new BigDecimal("640.5"), Unit.TONNES_PER_HOUR),
                Amounts.rate(number("640.5"), JSON.textNode("t/h")));
    }

    @Test
    void aQuantityWithNoUnitTwoUnitsOrANegativeNumberIsMalformed() {
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(number("1"), null)));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(number("1"), JSON.textNode(" "))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(JSON.textNode("1 t"), JSON.textNode("t"))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(JSON.textNode("-1 t"), null)));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(number("-1"), JSON.textNode("t"))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(JSON.textNode("lots"), JSON.textNode("t"))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(null, JSON.textNode("t"))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.amount(JSON.booleanNode(true), JSON.textNode("t"))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.rate(number("-1"), JSON.textNode("t/h"))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.rate(null, JSON.textNode("t/h"))));
        assertEquals("MALFORMED_UNIT", reason(() -> Amounts.rate(number("1"), null)));
    }

    // --- mapping rows ---

    @Test
    void aRowAppliesFromItsStartIncludedToItsEndExcluded() {
        MappingRow row = new MappingRow("a", TEN, TEN.plusSeconds(60));

        assertFalse(row.validAt(TEN.minusMillis(1)));
        assertTrue(row.validAt(TEN));
        assertTrue(row.validAt(TEN.plusSeconds(59)));
        assertFalse(row.validAt(TEN.plusSeconds(60)));
        assertTrue(new MappingRow("a", TEN, null).validAt(TEN.plus(Duration.ofDays(9999))));
    }

    @Test
    void theMappingValidAtTheTimeOfTheEventIsTheOneThatIsUsed() throws MappingException {
        InMemoryMappings store = new InMemoryMappings()
                .asset("sensors", "EQUIPMENT", "QC1", "old", Instant.EPOCH, TEN)
                .asset("sensors", "EQUIPMENT", "QC1", "new", TEN, null);
        Mappings mappings = new Mappings(store, new TestClock());

        assertEquals("old", mappings.identity("westports", "sensors", "EQUIPMENT", "QC1", TEN.minusSeconds(60)));
        assertEquals("new", mappings.identity("westports", "sensors", "EQUIPMENT", "QC1", TEN.plusSeconds(60)));
        assertEquals("new", mappings.identity("westports", "sensors", "EQUIPMENT", "QC1", TEN));
    }

    @Test
    void noRowIsUnknownAndTwoRowsAreAmbiguousNotPickedByLuck() {
        InMemoryMappings store = new InMemoryMappings()
                .asset("sensors", "EQUIPMENT", "QC1", "a", Instant.EPOCH, null)
                .asset("sensors", "EQUIPMENT", "QC1", "b", Instant.EPOCH, null);
        Mappings mappings = new Mappings(store, new TestClock());

        assertEquals(
                "UNKNOWN_MAPPING", reason(() -> mappings.identity("westports", "sensors", "EQUIPMENT", "QC9", TEN)));
        assertEquals(
                "UNKNOWN_MAPPING",
                reason(() -> mappings.identity("westports", "sensors", "STORAGE", "QC1", TEN)),
                "the entity type is part of the key");
        assertEquals(
                "UNKNOWN_MAPPING",
                reason(() -> mappings.identity("otherport", "sensors", "EQUIPMENT", "QC1", TEN)),
                "so is the port");
        assertEquals(
                "AMBIGUOUS_MAPPING", reason(() -> mappings.identity("westports", "sensors", "EQUIPMENT", "QC1", TEN)));
    }

    @Test
    void cargoCodesAreMappedAndAnUnmappedOneHasItsOwnReason() throws MappingException {
        Mappings mappings =
                new Mappings(new InMemoryMappings().code("itap", "COMMODITY", "PKE", "PKE-CANON"), new TestClock());

        assertEquals("PKE-CANON", mappings.cargoCode("itap", "COMMODITY", "PKE", TEN));
        assertEquals("UNKNOWN_CARGO_MAPPING", reason(() -> mappings.cargoCode("itap", "COMMODITY", "XYZ", TEN)));
        assertEquals("UNKNOWN_CARGO_MAPPING", reason(() -> mappings.cargoCode("itap", "CARGO_FAMILY", "PKE", TEN)));
    }

    @Test
    void aUnitMustFitTheFamilyAndAFamilyWithoutARuleIsNotChecked() throws MappingException {
        Mappings mappings = new Mappings(
                new InMemoryMappings().allow("DRY_BULK", Unit.TONNES).allow("BREAKBULK", Unit.TONNES, Unit.PIECES),
                new TestClock());

        mappings.requireFit("DRY_BULK", Unit.TONNES);
        mappings.requireFit("BREAKBULK", Unit.PIECES);
        mappings.requireFit("NEW_FAMILY", Unit.CUBIC_METRES);
        assertEquals("MALFORMED_UNIT", reason(() -> mappings.requireFit("DRY_BULK", Unit.PIECES)));
    }

    @Test
    void rowsAreReadOnceAMinuteOnTheInjectedClockUntilInvalidated() throws MappingException {
        TestClock clock = new TestClock();
        InMemoryMappings store = new InMemoryMappings();
        Mappings mappings = new Mappings(store, clock);
        assertEquals("UNKNOWN_MAPPING", reason(() -> mappings.identity("westports", "cbas", "BERTH", "B1", TEN)));

        store.asset("cbas", "BERTH", "B1", "berth-1");
        assertEquals(
                "UNKNOWN_MAPPING",
                reason(() -> mappings.identity("westports", "cbas", "BERTH", "B1", TEN)),
                "still the cached answer");

        clock.advance(Duration.ofSeconds(59));
        assertEquals("UNKNOWN_MAPPING", reason(() -> mappings.identity("westports", "cbas", "BERTH", "B1", TEN)));
        clock.advance(Duration.ofSeconds(1));
        assertEquals("berth-1", mappings.identity("westports", "cbas", "BERTH", "B1", TEN));

        assertEquals("UNKNOWN_MAPPING", reason(() -> mappings.identity("westports", "cbas", "BERTH", "B2", TEN)));
        store.asset("cbas", "BERTH", "B2", "berth-2");
        assertEquals("UNKNOWN_MAPPING", reason(() -> mappings.identity("westports", "cbas", "BERTH", "B2", TEN)));
        mappings.invalidate();
        assertEquals("berth-2", mappings.identity("westports", "cbas", "BERTH", "B2", TEN));
    }

    @Test
    void aSeedNeedsExactlyOneOfAssetCodeAndCanonicalId() {
        assertTrue(new MappingSeed("itap", "EQUIPMENT", "A", "MHC1", null).wellFormed());
        assertTrue(new MappingSeed("edic", "VESSEL_CALL", "A", null, "id").wellFormed());
        assertFalse(new MappingSeed("itap", "EQUIPMENT", "A", "MHC1", "id").wellFormed());
        assertFalse(new MappingSeed("itap", "EQUIPMENT", "A", null, " ").wellFormed());
        assertFalse(new MappingSeed(null, "EQUIPMENT", "A", "MHC1", null).wellFormed());
    }
}
