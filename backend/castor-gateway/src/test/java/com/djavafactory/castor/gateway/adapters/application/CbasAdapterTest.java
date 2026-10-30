package com.djavafactory.castor.gateway.adapters.application;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.support.AdapterFixtures;

import static com.djavafactory.castor.gateway.support.AdapterFixtures.assertGolden;
import static com.djavafactory.castor.gateway.support.AdapterFixtures.goldenInput;
import static com.djavafactory.castor.gateway.support.AdapterFixtures.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CbasAdapterTest {

    private final CbasAdapter adapter = new CbasAdapter(AdapterFixtures.support(AdapterFixtures.demoMappings()));

    private static final String MINIMAL = """
            { "msgId": "M-1", "callRef": "MV-X-1", "vessel": { "name": "MV X", "loa": 100, "beam": 20 },
              "status": "EXPECTED", "eta": "2026-10-30T06:00:00Z" }""";

    @Test
    void theExampleBecomesTheExpectedCanonicalJson() throws Rejection {
        List<CanonicalEvent> events = adapter.normalize(goldenInput(Source.CBAS, "cbas"));

        assertEquals(1, events.size());
        assertGolden("cbas", events.get(0));
    }

    @Test
    void aCallWithoutBerthOrMilestonesCarriesOnlyWhatWasSent() throws Rejection {
        CanonicalEvent event = adapter.normalize(record(Source.CBAS, MINIMAL)).get(0);

        assertEquals("vessel.call.updated", event.type());
        assertEquals("MV-X-1", event.entityId());
        assertEquals(AdapterFixtures.RECEIVED_AT, event.occurredAt(), "no arrival or departure: when it was received");
        List<String> fields = new java.util.ArrayList<>();
        event.payload().fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("callCode", "vesselName", "loaM", "beamM", "status", "eta"), fields);
    }

    @Test
    void aDepartureHappenedAtItsDepartureTime() throws Rejection {
        String departed = MINIMAL.replace(
                "\"status\": \"EXPECTED\"", "\"status\": \"departed\", \"atd\": \"2026-10-31T01:00:00+08:00\"");

        CanonicalEvent event = adapter.normalize(record(Source.CBAS, departed)).get(0);

        assertEquals("DEPARTED", event.payload().get("status").asText());
        assertEquals("2026-10-30T17:00:00Z", event.occurredAt().toString());
        assertEquals("2026-10-30T17:00:00Z", event.payload().get("atd").asText());
    }

    @Test
    void qualityComesFromTheSourceConfigurationNotFromTheMessage() throws Rejection {
        var observed = new CbasAdapter(AdapterFixtures.support(AdapterFixtures.demoMappings(), Quality.OBSERVED));

        assertEquals(
                Quality.OBSERVED,
                observed.normalize(record(Source.CBAS, MINIMAL)).get(0).quality());
        assertEquals(
                Quality.SIMULATED,
                adapter.normalize(record(Source.CBAS, MINIMAL)).get(0).quality());
    }

    @Test
    void aBerthThatIsNotMappedIsQuarantinedNotGuessed() {
        String body = MINIMAL.replace("\"status\"", "\"berthRef\": \"B99\", \"status\"");

        Rejection rejection = assertThrows(Rejection.class, () -> adapter.normalize(record(Source.CBAS, body)));

        assertEquals("UNKNOWN_MAPPING", rejection.reasonCode());
    }

    @Test
    void aMessageWithoutTheVesselOrTheEtaIsRefused() {
        assertEquals(
                Rejection.SCHEMA_INVALID,
                assertThrows(
                                Rejection.class,
                                () -> adapter.normalize(record(
                                        Source.CBAS, "{\"msgId\":\"M-2\",\"callRef\":\"C\",\"status\":\"EXPECTED\"}")))
                        .reasonCode());
        assertEquals(
                Rejection.SCHEMA_INVALID,
                assertThrows(
                                Rejection.class,
                                () -> adapter.normalize(record(Source.CBAS, MINIMAL.replace("\"eta\"", "\"etx\""))))
                        .reasonCode());
    }

    @Test
    void aStatusOutsideTheCatalogueIsRefusedByTheContract() {
        Rejection rejection = assertThrows(
                Rejection.class, () -> adapter.normalize(record(Source.CBAS, MINIMAL.replace("EXPECTED", "BERTHED"))));

        assertEquals(Rejection.SCHEMA_INVALID, rejection.reasonCode());
    }

    @Test
    void aSizeThatIsNotANumberIsRefused() {
        Rejection rejection = assertThrows(
                Rejection.class,
                () -> adapter.normalize(record(Source.CBAS, MINIMAL.replace("\"loa\": 100", "\"loa\": \"100\""))));

        assertEquals("loa must be a number", rejection.getMessage());
    }
}
