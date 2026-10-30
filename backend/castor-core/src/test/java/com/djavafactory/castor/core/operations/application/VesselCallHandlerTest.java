package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;
import com.djavafactory.castor.core.operations.domain.VesselStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VesselCallHandlerTest {

    private final ProjectionFixture f = new ProjectionFixture();

    @Test
    void creatingACallStoresItWithItsBerthAndProvenance() {
        f.handle(f.vesselCall, f.events.vesselCall("MV-AURORA-0412", "ALONGSIDE", f.berth));

        var call = f.calls.byCode.get("MV-AURORA-0412");
        assertEquals(VesselStatus.ALONGSIDE, call.status());
        assertEquals("MV Aurora", call.vesselName());
        assertEquals(f.berth, call.berthAssetId());
        assertEquals("SENSORS", call.provenance().source());
        assertEquals(0, call.version());
        var change = f.changes.all.get(0);
        assertEquals("vessel-call", change.entityType());
        assertEquals(call.id().toString(), change.entityId());
        assertEquals("MV-AURORA-0412", change.body().get("callCode"));
        assertEquals(f.berth.toString(), change.body().get("berthAssetId"));
    }

    @Test
    void anEventThatCarriesMilestonesOnlyLeavesEverythingElseAsItWas() {
        f.handle(f.vesselCall, f.events.vesselCall("MV-AURORA-0412", "EXPECTED", f.berth));
        f.clock.step(60);
        ObjectNode milestones =
                (ObjectNode) f.events.vesselCall("MV-AURORA-0412", null, null).payload();
        milestones.remove("vesselName");
        milestones.put("ata", "2026-10-30T08:20:00+08:00");
        f.handle(f.vesselCall, f.events.withPayload(f.events.vesselCall("MV-AURORA-0412", null, null), milestones));

        var call = f.calls.byCode.get("MV-AURORA-0412");
        assertEquals(VesselStatus.EXPECTED, call.status());
        assertEquals("MV Aurora", call.vesselName());
        assertEquals(f.berth, call.berthAssetId());
        assertEquals(Instant.parse("2026-10-30T00:20:00Z"), call.ata());
        assertEquals(1, call.version());
    }

    @Test
    void sizesKeepTheOneDecimalOfTheColumns() {
        ObjectNode payload =
                (ObjectNode) f.events.vesselCall("C1", "EXPECTED", null).payload();
        payload.put("loaM", 189.94).put("beamM", 32.25);
        f.handle(f.vesselCall, f.events.withPayload(f.events.vesselCall("C1", "EXPECTED", null), payload));

        var call = f.calls.byCode.get("C1");
        assertEquals(new BigDecimal("189.9"), call.loaM());
        assertEquals(new BigDecimal("32.3"), call.beamM());
    }

    @Test
    void aNewCallWithoutAStatusGetsTheOneItsMilestonesImply() {
        f.handle(f.vesselCall, f.events.vesselCall("C-NEW", null, null));
        assertEquals(VesselStatus.EXPECTED, f.calls.byCode.get("C-NEW").status());

        ObjectNode withAta =
                (ObjectNode) f.events.vesselCall("C-ATA", null, null).payload();
        withAta.put("ata", "2026-10-30T08:00:00Z");
        f.handle(f.vesselCall, f.events.withPayload(f.events.vesselCall("C-ATA", null, null), withAta));
        assertEquals(VesselStatus.ALONGSIDE, f.calls.byCode.get("C-ATA").status());
    }

    @Test
    void aBerthThatIsNotInThePublishedLayoutIsUnknown() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.vesselCall, f.events.vesselCall("C1", "ALONGSIDE", java.util.UUID.randomUUID())));

        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
        assertTrue(f.calls.byCode.isEmpty());
    }

    @Test
    void anAssetThatIsNotABerthIsNotAcceptedAsOne() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.vesselCall, f.events.vesselCall("C1", "ALONGSIDE", f.pad)));

        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
    }

    @Test
    void anOlderUpdateIsKeptAsEvidenceAndOpensOneStaleItem() {
        f.clock.step(120);
        f.handle(f.vesselCall, f.events.vesselCall("C1", "DEPARTED", f.berth));
        f.handle(f.vesselCall, f.events.at(f.events.vesselCall("C1", "ALONGSIDE", f.berth), ProjectionFixture.T0));

        var call = f.calls.byCode.get("C1");
        assertEquals(VesselStatus.DEPARTED, call.status());
        assertEquals(0, call.version());
        assertEquals(1, f.reconciliation.items.size());
        assertEquals(call.id().toString(), f.reconciliation.items.get(0).entityId());
        assertEquals(1, f.changes.all.size());
        assertNull(f.calls.byCode.get("C1").atd());
    }

    @Test
    void aStatusOutsideTheCatalogueIsInvalid() {
        var e = assertThrows(
                RejectedEventException.class, () -> f.handle(f.vesselCall, f.events.vesselCall("C1", "SINKING", null)));
        assertEquals(DeadLetterReason.INVALID_EVENT, e.reason());
    }
}
