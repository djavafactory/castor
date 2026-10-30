package com.djavafactory.castor.gateway.mapping.application;

import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.MappingSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PRD 19.2 end to end: a malformed unit or an unmapped cargo code is quarantined with its reason, and nothing of it
 * reaches the outbox or Kafka. A good record with the same shape goes through, so the test is about the rule and not the
 * set-up.
 */
@QuarkusTest
class UnitsAndCargoCodesIT extends MappingSupport {

    @BeforeEach
    void assets() {
        mapAsset("itap", "STORAGE", "PAD-A1", "asset-pad-a1");
        mapAsset("itap", "STORAGE", "SL1", "asset-sl1");
        mapAsset("sensors", "EQUIPMENT", "CV1-RATE", "asset-cv1");
        mapAsset("sensors", "STORAGE", "TK1-LEVEL", "asset-tk1");
    }

    private static String itap(String id, String family, String commodity, String qty, String uom) {
        return """
                {"txnId":"%s","lotRef":"L-%s","family":"%s","commodity":"%s","qty":%s,"uom":%s,
                 "toLoc":"PAD-A1","ts":"2026-10-29T09:00:00Z"}""".formatted(id, id, family, commodity, qty, uom);
    }

    private void assertQuarantinedAndNeverPublished(String source, String body, String sourceEventId, String reason) {
        String receptionId = accepted(source, body);

        normalizer.runOnce();
        relay.runOnce();

        assertEquals("QUARANTINED", inboxStatus(receptionId));
        assertEquals(reason, quarantineReason(receptionId));
        assertEquals(0, outboxRows(), "nothing reaches the outbox");
        assertEquals(Set.of(), kafkaSourceEvents(Set.of(sourceEventId)), "nothing reaches Kafka");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            unknown unit code kg            | DRY_BULK | COAL | 10     | "kg"        | MALFORMED_UNIT
            wrong case of a known code      | DRY_BULK | COAL | 10     | "T"         | MALFORMED_UNIT
            no unit at all                  | DRY_BULK | COAL | 10     | null        | MALFORMED_UNIT
            a rate as an amount             | DRY_BULK | COAL | 10     | "t/h"       | MALFORMED_UNIT
            two units in one message        | DRY_BULK | COAL | "10 t" | "MT"        | MALFORMED_UNIT
            a negative quantity             | DRY_BULK | COAL | -4     | "t"         | MALFORMED_UNIT
            cubic metres of dry bulk        | DRY_BULK | COAL | 10     | "m3"        | MALFORMED_UNIT
            pieces of dry bulk              | DRY_BULK | COAL | 10     | "pcs"       | MALFORMED_UNIT
            tonnes of a container           | CONTAINER | CONT | 10    | "t"         | MALFORMED_UNIT
            unmapped commodity              | DRY_BULK | SLIME | 10    | "t"         | UNKNOWN_CARGO_MAPPING
            unmapped family                 | MYSTERY  | COAL | 10     | "t"         | UNKNOWN_CARGO_MAPPING
            """)
    void aMalformedUnitOrAnUnmappedCargoCodeEndsInQuarantineWithItsReason(
            String name, String family, String commodity, String qty, String uom, String reason) {
        String id = "U-" + Math.abs(name.hashCode());

        assertQuarantinedAndNeverPublished("itap", itap(id, family, commodity, qty.strip(), uom.strip()), id, reason);
    }

    @Test
    void aRateSensorWithAnAmountUnitIsQuarantinedButTheRateUnitGoesThrough() {
        String bad = """
                {"sensorId":"CV1-RATE","kind":"CONVEYOR_RATE","values":{"rate":640,"unit":"t"},
                 "ts":"2026-10-29T09:00:00Z"}""";
        assertQuarantinedAndNeverPublished(
                "sensors", bad, "CV1-RATE:CONVEYOR_RATE:2026-10-29T09:00:00Z", "MALFORMED_UNIT");

        accepted("sensors", bad.replace("\"t\"", "\"t/h\"").replace("09:00:00Z", "09:00:01Z"));
        normalizer.runOnce();
        assertEquals("TONNES_PER_HOUR", Db.value(dataSource, "select payload #>> '{payload,unit}' from ingest.outbox"));
    }

    @Test
    void aLevelSensorWithAnUnmappedCommodityIsQuarantined() {
        assertQuarantinedAndNeverPublished(
                "sensors", """
                {"sensorId":"TK1-LEVEL","kind":"TANK_LEVEL","values":{"commodity":"SLIME","quantity":5,"uom":"m3"},
                 "ts":"2026-10-29T09:00:00Z"}""", "TK1-LEVEL:TANK_LEVEL:2026-10-29T09:00:00Z", "UNKNOWN_CARGO_MAPPING");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"t, TONNES", "MT, TONNES"})
    void theCodesOfTheTableBecomeTheirCanonicalUnitsOnKafka(String code, String unit) {
        String id = "G-" + code;
        accepted("itap", itap(id, "DRY_BULK", "COAL", "12.5", "\"" + code + "\""));

        normalizer.runOnce();
        relay.runOnce();

        assertEquals(Set.of(id), kafkaSourceEvents(Set.of(id)));
        assertEquals(unit, Db.value(dataSource, "select payload #>> '{payload,unit}' from ingest.outbox"));
        assertEquals("12.5", Db.value(dataSource, "select payload #>> '{payload,quantity}' from ingest.outbox"));
    }

    @Test
    void pieceAndCubicMetreCodesFitTheFamiliesThatAreCountedOrPoured() {
        accepted("itap", itap("P-1", "BREAKBULK", "STEEL", "40", "\"pcs\""));
        accepted("itap", itap("P-2", "LIQUID_BULK", "OIL", "300", "\"m3\""));

        assertEquals(2, normalizer.runOnce());

        assertEquals(
                java.util.List.of("PIECES", "CUBIC_METRES"),
                Db.column(dataSource, "select payload #>> '{payload,unit}' from ingest.outbox order by id"));
    }
}
