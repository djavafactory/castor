package com.djavafactory.castor.gateway.adapters.application;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.support.AdapterFixtures;

import static com.djavafactory.castor.gateway.support.AdapterFixtures.assertGolden;
import static com.djavafactory.castor.gateway.support.AdapterFixtures.goldenInput;
import static com.djavafactory.castor.gateway.support.AdapterFixtures.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SensorsAdapterTest {

    private final SensorsAdapter adapter = new SensorsAdapter(AdapterFixtures.support(AdapterFixtures.demoMappings()));

    private static String sensor(String id, String kind, String values) {
        return "{\"sensorId\":\"%s\",\"kind\":\"%s\",\"values\":%s,\"ts\":\"2026-10-29T08:20:00Z\"}"
                .formatted(id, kind, values);
    }

    private String reasonOf(String body) {
        return assertThrows(Rejection.class, () -> adapter.normalize(record(Source.SENSORS, body)))
                .reasonCode();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sensors-gps",
                "sensors-status",
                "sensors-conveyor",
                "sensors-tank",
                "sensors-silo",
                "sensors-camera"
            })
    void theExampleOfEachKindBecomesTheExpectedCanonicalJson(String name) throws Rejection {
        List<CanonicalEvent> events = adapter.normalize(goldenInput(Source.SENSORS, name));

        assertEquals(1, events.size());
        assertGolden(name, events.get(0));
    }

    @Test
    void aSensorThatIsNotMappedIsQuarantinedNotGuessed() {
        assertEquals("UNKNOWN_MAPPING", reasonOf(sensor("GPS-NOBODY", "GPS", "{\"lon\":1,\"lat\":1,\"accuracyM\":1}")));
    }

    @Test
    void theSameSensorIdOfAnotherKindOfAssetIsAnotherMapping() {
        // GPS-MHC1 is equipment; as a tank level sensor it is not mapped
        assertEquals(
                "UNKNOWN_MAPPING",
                reasonOf(sensor("GPS-MHC1", "TANK_LEVEL", "{\"commodity\":\"OIL\",\"quantity\":1,\"uom\":\"m3\"}")));
    }

    @Test
    void aKindThatDoesNotExistIsRefused() {
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(sensor("GPS-MHC1", "RADAR", "{}")));
    }

    @Test
    void aPositionWithoutItsAccuracyIsRefusedByTheContract() {
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(sensor("GPS-MHC1", "GPS", "{\"lon\":101.3,\"lat\":2.9}")));
    }

    @Test
    void aStatusOutsideTheCatalogueIsRefusedByTheContract() {
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(sensor("GPS-MHC1", "STATUS", "{\"status\":\"EXPLODING\"}")));
    }

    @Test
    void aConveyorRateNeedsARateUnitNotAnAmountUnit() {
        assertEquals("MALFORMED_UNIT", reasonOf(sensor("CV1-RATE", "CONVEYOR_RATE", "{\"rate\":10,\"unit\":\"t\"}")));
        assertEquals("MALFORMED_UNIT", reasonOf(sensor("CV1-RATE", "CONVEYOR_RATE", "{\"rate\":10}")));
        assertEquals(
                "MALFORMED_UNIT", reasonOf(sensor("CV1-RATE", "CONVEYOR_RATE", "{\"rate\":-10,\"unit\":\"t/h\"}")));
    }

    @Test
    void aVehicleRateIsNotATonnageRate() throws Rejection {
        CanonicalEvent event = adapter.normalize(record(
                        Source.SENSORS, sensor("CV1-RATE", "CONVEYOR_RATE", "{\"rate\":30,\"unit\":\"units/h\"}")))
                .get(0);

        assertEquals("VEHICLES_PER_HOUR", event.payload().get("unit").asText());
    }

    @Test
    void aLevelWithTwoUnitsOrNoneOrANegativeAmountIsMalformed() {
        String two = "{\"commodity\":\"OIL\",\"quantity\":\"5 m3\",\"uom\":\"m3\"}";
        String none = "{\"commodity\":\"OIL\",\"quantity\":5}";
        String negative = "{\"commodity\":\"OIL\",\"quantity\":-5,\"uom\":\"m3\"}";
        assertEquals("MALFORMED_UNIT", reasonOf(sensor("TK1-LEVEL", "TANK_LEVEL", two)));
        assertEquals("MALFORMED_UNIT", reasonOf(sensor("TK1-LEVEL", "TANK_LEVEL", none)));
        assertEquals("MALFORMED_UNIT", reasonOf(sensor("TK1-LEVEL", "TANK_LEVEL", negative)));
    }

    @Test
    void aCommodityThatIsNotMappedIsQuarantinedWithItsOwnReason() {
        assertEquals(
                "UNKNOWN_CARGO_MAPPING",
                reasonOf(sensor("TK1-LEVEL", "TANK_LEVEL", "{\"commodity\":\"SLIME\",\"quantity\":5,\"uom\":\"m3\"}")));
    }

    @Test
    void aCameraCountMustBeAWholeNumber() {
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(sensor("CAM-G1", "CAMERA_COUNT", "{\"count\":1.5}")));
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(sensor("CAM-G1", "CAMERA_COUNT", "{}")));
    }
}
