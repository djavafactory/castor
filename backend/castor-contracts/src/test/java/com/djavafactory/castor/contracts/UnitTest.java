package com.djavafactory.castor.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hasTheAmountAndRateCodesOfTheContract() {
        assertEquals(
                Set.of(
                        "TONNES",
                        "PIECES",
                        "CUBIC_METRES",
                        "VEHICLES",
                        "TRUCKS",
                        "LIFTS",
                        "TONNES_PER_HOUR",
                        "PIECES_PER_HOUR",
                        "CUBIC_METRES_PER_HOUR",
                        "VEHICLES_PER_HOUR",
                        "TRUCKS_PER_HOUR",
                        "LIFTS_PER_HOUR"),
                EnumSet.allOf(Unit.class).stream().map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void everyUnitHasItsOwnDimension() {
        assertEquals(
                Unit.values().length,
                EnumSet.allOf(Unit.class).stream()
                        .map(Unit::dimension)
                        .distinct()
                        .count());
    }

    @ParameterizedTest
    @EnumSource(Unit.class)
    void amountAndRateUnitsPairUp(Unit unit) {
        if (unit.isRate()) {
            assertEquals(unit, unit.amount().orElseThrow().perHour().orElseThrow());
            assertTrue(unit.perHour().isEmpty());
        } else {
            assertEquals(unit, unit.perHour().orElseThrow().amount().orElseThrow());
            assertTrue(unit.amount().isEmpty());
        }
    }

    @Test
    void parseRejectsUnknownCodesInsteadOfGuessing() {
        assertEquals(Unit.TONNES_PER_HOUR, Unit.parse("TONNES_PER_HOUR"));
        assertThrows(IllegalArgumentException.class, () -> Unit.parse("tonnes"));
        assertThrows(IllegalArgumentException.class, () -> Unit.parse("T/H"));
        assertThrows(IllegalArgumentException.class, () -> Unit.parse(null));
    }

    @Test
    void valuesOfDifferentDimensionsCannotBeConverted() {
        BigDecimal ten = BigDecimal.TEN;

        assertEquals(ten, Unit.TONNES.convert(ten, Unit.TONNES));
        assertThrows(UnitMismatchException.class, () -> Unit.TONNES.convert(ten, Unit.PIECES));
        assertThrows(UnitMismatchException.class, () -> Unit.TONNES_PER_HOUR.convert(ten, Unit.VEHICLES_PER_HOUR));
        assertThrows(UnitMismatchException.class, () -> Unit.TONNES.convert(ten, Unit.TONNES_PER_HOUR));
    }

    @Test
    void quantitiesAddOnlyInTheSameUnit() {
        Quantity sum =
                new Quantity(new BigDecimal("1.5"), Unit.TONNES).plus(new Quantity(new BigDecimal("2"), Unit.TONNES));

        assertEquals(new BigDecimal("3.5"), sum.value());
        assertEquals(Unit.TONNES, sum.unit());
        assertEquals(
                new BigDecimal("-0.5"),
                new Quantity(BigDecimal.ONE, Unit.TRUCKS)
                        .minus(new Quantity(new BigDecimal("1.5"), Unit.TRUCKS))
                        .value());
        assertThrows(
                UnitMismatchException.class,
                () -> new Quantity(BigDecimal.ONE, Unit.TRUCKS).plus(new Quantity(BigDecimal.ONE, Unit.VEHICLES)));
    }

    @Test
    void schemasUseExactlyTheUnitCodes() throws IOException {
        Set<String> codes = EnumSet.allOf(Unit.class).stream().map(Enum::name).collect(Collectors.toSet());
        Set<String> used = enumValues("cargo.lot.registered", "unit");
        used.addAll(enumValues("cargo.lot.moved", "unit"));
        used.addAll(enumValues("storage.level.observed", "unit"));
        used.addAll(enumValues("inventory.adjusted", "unit"));
        used.addAll(enumValues("flow.rate.observed", "unit"));

        assertTrue(codes.containsAll(used), "schema unit codes unknown to Unit: " + used);
        assertEquals(
                EnumSet.allOf(Unit.class).stream()
                        .filter(Unit::isRate)
                        .map(Enum::name)
                        .collect(Collectors.toSet()),
                enumValues("flow.rate.observed", "unit"),
                "flow.rate.observed accepts rates and nothing else");
        assertTrue(
                enumValues("cargo.lot.registered", "unit").stream()
                        .noneMatch(c -> Unit.parse(c).isRate()),
                "a lot quantity is an amount, never a rate");
    }

    private static Set<String> enumValues(String type, String property) throws IOException {
        try (InputStream in = UnitTest.class.getResourceAsStream("/schemas/v1/" + type + ".json")) {
            JsonNode values =
                    JSON.readTree(in).path("properties").path(property).path("enum");
            return StreamSupport.stream(values.spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.toCollection(java.util.HashSet::new));
        }
    }
}
