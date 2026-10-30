package com.djavafactory.castor.core.operations.application;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.VesselStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** README "Decisions: system of record per field" applied to the vessel call. */
class VesselCallPrecedenceHandlerTest {

    private static final String CALL = "MV-AURORA-0412";

    private final ProjectionFixture f = new ProjectionFixture();

    private void report(String source, String field, String value) {
        f.clock.step(60);
        f.handle(f.vesselCall, f.events.callField(source, CALL, field, value));
    }

    private com.djavafactory.castor.core.operations.domain.VesselCall call() {
        return f.calls.byCode.get(CALL);
    }

    @Test
    void edicWinsTheArrivalAndAThirtyMinuteDisagreementOpensAConflict() {
        report("cbas", "ata", "2026-10-30T08:20:00Z");
        report("edic", "ata", "2026-10-30T08:50:00Z");

        assertEquals(Instant.parse("2026-10-30T08:50:00Z"), call().ata());
        var conflicts = f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT);
        assertEquals(1, conflicts.size());
        assertEquals(call().id().toString(), conflicts.get(0).entityId());
        assertEquals("ata", conflicts.get(0).detail().get("field"));
        assertEquals(30L, conflicts.get(0).detail().get("differenceMinutes"));
        // both values stay in the history
        assertEquals(2, f.values.history(call().id(), "ata").size());
    }

    @Test
    void theLowerSourceNeverOverridesAndTheConflictOpensOnItsLateArrivalToo() {
        report("edic", "ata", "2026-10-30T08:50:00Z");
        report("cbas", "ata", "2026-10-30T08:20:00Z");

        assertEquals(Instant.parse("2026-10-30T08:50:00Z"), call().ata());
        assertEquals(1, f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).size());
        // the ignored value changed nothing in the call
        assertEquals(0, call().version());
    }

    @Test
    void cbasWinsTheEstimateOverEdic() {
        report("edic", "eta", "2026-10-30T06:00:00Z");
        report("cbas", "eta", "2026-10-30T07:00:00Z");
        report("edic", "eta", "2026-10-30T09:00:00Z");

        assertEquals(Instant.parse("2026-10-30T07:00:00Z"), call().eta());
    }

    @Test
    void theLowerSourceFillsAFieldOnlyWhileTheHigherOneHasNone() {
        report("cbas", "ata", "2026-10-30T08:20:00Z");
        report("edic", "atd", "2026-10-31T01:00:00Z");

        assertEquals(Instant.parse("2026-10-30T08:20:00Z"), call().ata());
        assertEquals(Instant.parse("2026-10-31T01:00:00Z"), call().atd());
        assertTrue(f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).isEmpty());
    }

    @Test
    void timesWithinFifteenMinutesAgreeAndOnlyTheBetterSourceIsKept() {
        report("cbas", "ata", "2026-10-30T08:20:00Z");
        report("edic", "ata", "2026-10-30T08:35:00Z");

        assertEquals(Instant.parse("2026-10-30T08:35:00Z"), call().ata());
        assertTrue(f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).isEmpty());
    }

    @Test
    void aConflictThatIsStillOpenIsNotOpenedAgainForTheSameField() {
        report("cbas", "ata", "2026-10-30T08:20:00Z");
        report("edic", "ata", "2026-10-30T08:50:00Z");
        report("cbas", "ata", "2026-10-30T08:21:00Z");

        assertEquals(1, f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT).size());
    }

    @Test
    void statusAndBerthDifferencesAreConflictsWhateverTheirSize() {
        report("cbas", "status", "ALONGSIDE");
        report("edic", "status", "DEPARTED");

        assertEquals(VesselStatus.ALONGSIDE, call().status());
        var conflicts = f.reconciliation.of(ReconciliationKind.SOURCE_CONFLICT);
        assertEquals(1, conflicts.size());
        assertEquals("status", conflicts.get(0).detail().get("field"));
        assertNull(conflicts.get(0).detail().get("differenceMinutes"));
    }

    @Test
    void anEventIsStaleOnlyAgainstEarlierEventsOfItsOwnSource() {
        // edic speaks later than cbas but its event happened earlier: still not stale, the rule ranks sources
        f.clock.step(3600);
        f.handle(f.vesselCall, f.events.callField("cbas", CALL, "ata", "2026-10-30T08:20:00Z"));
        f.handle(
                f.vesselCall,
                f.events.at(f.events.callField("edic", CALL, "ata", "2026-10-30T08:50:00Z"), ProjectionFixture.T0));

        assertEquals(Instant.parse("2026-10-30T08:50:00Z"), call().ata());
        assertTrue(f.reconciliation.of(ReconciliationKind.STALE_EVENT).isEmpty());

        // an older event of the same source is stale
        f.handle(
                f.vesselCall,
                f.events.at(
                        f.events.callField("edic", CALL, "ata", "2026-10-30T07:00:00Z"),
                        ProjectionFixture.T0.minusSeconds(60)));
        assertEquals(1, f.reconciliation.of(ReconciliationKind.STALE_EVENT).size());
        assertEquals(Instant.parse("2026-10-30T08:50:00Z"), call().ata());
    }
}
