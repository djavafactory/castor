package com.djavafactory.castor.gateway.adapters.application;

import java.util.ArrayList;
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

class EdicAdapterTest {

    private final EdicAdapter adapter = new EdicAdapter(AdapterFixtures.support(AdapterFixtures.demoMappings()));

    private static String edi(String text) {
        return "{\"edi\":\"" + text + "\"}";
    }

    private String reasonOf(String body) {
        return assertThrows(Rejection.class, () -> adapter.normalize(record(Source.EDIC, body)))
                .reasonCode();
    }

    @Test
    void theExampleBecomesTheExpectedCanonicalJson() throws Rejection {
        List<CanonicalEvent> events = adapter.normalize(goldenInput(Source.EDIC, "edic"));

        assertEquals(1, events.size());
        assertGolden("edic", events.get(0));
    }

    @Test
    void anEdiMilestoneCarriesTheMilestoneAndNeverAStatusOrABerth() throws Rejection {
        for (String[] milestone :
                new String[][] {{"ARRIVED", "ata"}, {"DEPARTED", "atd"}, {"ETA", "eta"}, {"ETD", "etd"}}) {
            CanonicalEvent event = adapter.normalize(record(
                            Source.EDIC,
                            edi("MSG+E-9'CALL+EDI-AURORA-0412'EVT+" + milestone[0] + "'DTM+202610290620'")))
                    .get(0);

            List<String> fields = new ArrayList<>();
            event.payload().fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("callCode", milestone[1]), fields, milestone[0]);
            assertEquals(
                    "2026-10-29T06:20:00Z", event.payload().get(milestone[1]).asText());
        }
    }

    @Test
    void theReferencesOfTheSenderAreMappedToTheVesselCallId() throws Rejection {
        CanonicalEvent event =
                adapter.normalize(goldenInput(Source.EDIC, "edic")).get(0);

        assertEquals("MV-AURORA-0412", event.entityId());
        assertEquals("MV-AURORA-0412", event.payload().get("callCode").asText());
    }

    @Test
    void aCallRefThatIsNotMappedIsQuarantinedNotGuessed() {
        assertEquals("UNKNOWN_MAPPING", reasonOf(edi("MSG+E-2'CALL+EDI-UNKNOWN'EVT+ARRIVED'DTM+202610290620'")));
    }

    @Test
    void aMilestoneThatWouldBeAPhysicalMovementIsRefused() {
        assertEquals(
                Rejection.SCHEMA_INVALID, reasonOf(edi("MSG+E-3'CALL+EDI-AURORA-0412'EVT+BERTHED'DTM+202610290620'")));
    }

    @Test
    void aMessageThatIsNotEdiIsRefusedWithTheProblem() {
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(edi("MSG+E-4'CALL+EDI-AURORA-0412'EVT+ARRIVED'")));
        assertEquals(
                Rejection.SCHEMA_INVALID, reasonOf(edi("MSG+E-5'CALL+EDI-AURORA-0412'EVT+ARRIVED'DTM+yesterday'")));
        assertEquals(Rejection.SCHEMA_INVALID, reasonOf(edi("CALL+EDI-AURORA-0412'EVT+ARRIVED'DTM+202610290620'")));
    }
}
