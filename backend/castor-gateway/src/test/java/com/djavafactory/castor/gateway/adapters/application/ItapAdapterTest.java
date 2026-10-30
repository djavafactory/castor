package com.djavafactory.castor.gateway.adapters.application;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.support.AdapterFixtures;

import static com.djavafactory.castor.gateway.support.AdapterFixtures.assertGolden;
import static com.djavafactory.castor.gateway.support.AdapterFixtures.goldenInput;
import static com.djavafactory.castor.gateway.support.AdapterFixtures.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItapAdapterTest {

    private final ItapAdapter adapter = new ItapAdapter(AdapterFixtures.support(AdapterFixtures.demoMappings()));

    private static String registered(String qty, String uom) {
        return """
                { "txnId": "T-1", "lotRef": "L-1", "family": "DRY_BULK", "commodity": "COAL", "qty": %s, "uom": %s,
                  "toLoc": "PAD-A1", "ts": "2026-10-29T07:00:00Z" }""".formatted(qty, uom);
    }

    private String reasonOf(String body) {
        return assertThrows(Rejection.class, () -> adapter.normalize(record(Source.ITAP, body)))
                .reasonCode();
    }

    @Test
    void aNewLotBecomesCargoLotRegistered() throws Rejection {
        List<CanonicalEvent> events = adapter.normalize(goldenInput(Source.ITAP, "itap-registered"));

        assertEquals(1, events.size());
        assertGolden("itap-registered", events.get(0));
    }

    @Test
    void aLotThatLeavesALocationBecomesCargoLotMoved() throws Rejection {
        List<CanonicalEvent> events = adapter.normalize(goldenInput(Source.ITAP, "itap-moved"));

        assertEquals(1, events.size());
        assertGolden("itap-moved", events.get(0));
    }

    @Test
    void aLotNeedsNoMappingRowBecauseItsSourceCreatedIt() throws Rejection {
        CanonicalEvent event = adapter.normalize(record(Source.ITAP, registered("10", "\"t\"")))
                .get(0);

        assertEquals("itap:L-1", event.entityId());
    }

    @Test
    void aMoveWithoutTheHandlingEquipmentHasNoHandledBy() throws Rejection {
        String body = """
                { "txnId": "T-2", "lotRef": "L-1", "family": "DRY_BULK", "commodity": "COAL", "qty": 5, "uom": "t",
                  "fromLoc": "PAD-A1", "toLoc": "SL1", "ts": "2026-10-29T07:00:00Z" }""";

        CanonicalEvent event = adapter.normalize(record(Source.ITAP, body)).get(0);

        assertEquals(null, event.payload().get("handledBy"));
    }

    @Test
    void everyKnownCodeOfTheUnitTableMapsToItsCanonicalUnit() throws Rejection {
        assertEquals(
                "TONNES",
                adapter.normalize(record(Source.ITAP, registered("1", "\"t\"")))
                        .get(0)
                        .payload()
                        .get("unit")
                        .asText());
        assertEquals(
                "TONNES",
                adapter.normalize(record(Source.ITAP, registered("1", "\"MT\"")))
                        .get(0)
                        .payload()
                        .get("unit")
                        .asText());
    }

    @Test
    void aUnitThatIsNotInTheTableIsMalformed() {
        assertEquals("MALFORMED_UNIT", reasonOf(registered("10", "\"kg\"")));
        assertEquals("MALFORMED_UNIT", reasonOf(registered("10", "\"T\"")));
        assertEquals("MALFORMED_UNIT", reasonOf(registered("10", "null")));
    }

    @Test
    void aRateWhereAnAmountBelongsIsMalformed() {
        assertEquals("MALFORMED_UNIT", reasonOf(registered("10", "\"t/h\"")));
    }

    @Test
    void twoUnitsInOneMessageAreMalformedEvenWhenTheyAgree() {
        assertEquals("MALFORMED_UNIT", reasonOf(registered("\"10 t\"", "\"t\"")));
        assertEquals("MALFORMED_UNIT", reasonOf(registered("\"10 t\"", "\"pcs\"")));
    }

    @Test
    void aUnitInsideTheQuantityTextIsAcceptedWhenNothingElseNamesOne() throws Rejection {
        CanonicalEvent event = adapter.normalize(record(Source.ITAP, registered("\"12.5 MT\"", "null")))
                .get(0);

        assertEquals("TONNES", event.payload().get("unit").asText());
        assertEquals("12.5", event.payload().get("quantity").decimalValue().toPlainString());
    }

    @Test
    void aNegativeQuantityIsMalformedNeverCorrected() {
        assertEquals("MALFORMED_UNIT", reasonOf(registered("-1", "\"t\"")));
    }

    @Test
    void aUnitThatDoesNotFitTheCargoFamilyIsMalformed() {
        assertEquals("MALFORMED_UNIT", reasonOf(registered("10", "\"m3\"")), "dry bulk is not measured in m3");
        assertEquals("MALFORMED_UNIT", reasonOf(registered("10", "\"pcs\"")), "nor in pieces");
    }

    @Test
    void aUnitThatFitsAnotherFamilyIsAccepted() throws Rejection {
        String liquid =
                registered("10", "\"m3\"").replace("DRY_BULK", "LIQUID_BULK").replace("COAL", "OIL");

        assertEquals(
                "CUBIC_METRES",
                adapter.normalize(record(Source.ITAP, liquid))
                        .get(0)
                        .payload()
                        .get("unit")
                        .asText());
    }

    @Test
    void anUnmappedFamilyOrCommodityIsQuarantinedWithItsOwnReason() {
        assertEquals("UNKNOWN_CARGO_MAPPING", reasonOf(registered("1", "\"t\"").replace("DRY_BULK", "MYSTERY")));
        assertEquals("UNKNOWN_CARGO_MAPPING", reasonOf(registered("1", "\"t\"").replace("COAL", "MYSTERY")));
    }

    @Test
    void aLocationThatIsNotMappedIsQuarantinedNotGuessed() {
        String body = registered("1", "\"t\"")
                .replace("\"toLoc\": \"PAD-A1\"", "\"fromLoc\": \"PAD-A1\", \"toLoc\": \"NOWHERE\"");

        assertEquals("UNKNOWN_MAPPING", reasonOf(body));
    }

    @Test
    void aMoveToNowhereIsRefused() {
        String body = registered("1", "\"t\"").replace("\"toLoc\": \"PAD-A1\"", "\"fromLoc\": \"PAD-A1\"");

        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(body));
    }

    @Test
    void aMoveOfNothingIsRefusedByTheContract() {
        String body = registered("0", "\"t\"")
                .replace("\"toLoc\": \"PAD-A1\"", "\"fromLoc\": \"PAD-A1\", \"toLoc\": \"SL1\"");

        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(body));
    }

    @Test
    void aMessageWithoutATimestampIsRefused() {
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(registered("1", "\"t\"").replace("\"ts\"", "\"time\"")));
    }
}
