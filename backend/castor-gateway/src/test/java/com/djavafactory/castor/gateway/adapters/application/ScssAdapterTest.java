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

class ScssAdapterTest {

    private final ScssAdapter adapter = new ScssAdapter(AdapterFixtures.support(AdapterFixtures.demoMappings()));

    @Test
    void theExampleBecomesTheExpectedCanonicalJson() throws Rejection {
        List<CanonicalEvent> events = adapter.normalize(goldenInput(Source.SCSS, "scss"));

        assertEquals(1, events.size());
        assertGolden("scss", events.get(0));
    }

    @Test
    void aTruckVisitIsCreatedByItsSourceAndNeedsNoMapping() throws Rejection {
        CanonicalEvent event =
                adapter.normalize(goldenInput(Source.SCSS, "scss")).get(0);

        assertEquals("scss:WXY1234-0001", event.entityId());
        assertEquals("asset-g1", event.aggregateId(), "the events of a gate are ordered by the gate");
    }

    @Test
    void aCsvRowIsTextAndGivesTheSameEventAsTheJsonRecord() throws Rejection {
        String csvRow = "{\"truckVisit\":\"WXY1234-0001\",\"gateRef\":\"G1\",\"direction\":\"IN\",\"lane\":\"2\","
                + "\"ts\":\"2026-10-29T08:15:30Z\"}";

        assertGolden("scss", adapter.normalize(record(Source.SCSS, csvRow)).get(0));
    }

    @Test
    void aGateThatIsNotMappedIsQuarantinedNotGuessed() {
        String body =
                "{\"truckVisit\":\"V1\",\"gateRef\":\"G9\",\"direction\":\"OUT\",\"ts\":\"2026-10-29T08:15:30Z\"}";

        Rejection rejection = assertThrows(Rejection.class, () -> adapter.normalize(record(Source.SCSS, body)));

        assertEquals("UNKNOWN_MAPPING", rejection.reasonCode());
    }

    @Test
    void aDirectionThatIsNeitherInNorOutIsRefusedByTheContract() {
        String body =
                "{\"truckVisit\":\"V1\",\"gateRef\":\"G1\",\"direction\":\"SIDEWAYS\",\"ts\":\"2026-10-29T08:15:30Z\"}";

        Rejection rejection = assertThrows(Rejection.class, () -> adapter.normalize(record(Source.SCSS, body)));

        assertEquals(Rejection.SCHEMA_INVALID, rejection.reasonCode());
    }

    @Test
    void aTransactionWithoutALaneIsStillAGateTransaction() throws Rejection {
        String body = "{\"truckVisit\":\"V1\",\"gateRef\":\"G1\",\"direction\":\"OUT\",\"lane\":\"\","
                + "\"ts\":\"2026-10-29T08:15:30Z\"}";

        CanonicalEvent event = adapter.normalize(record(Source.SCSS, body)).get(0);

        assertEquals(null, event.payload().get("lane"));
    }
}
