package com.djavafactory.castor.core.operations.application;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GateTransactionHandlerTest {

    private final ProjectionFixture f = new ProjectionFixture();

    private void tx(String truck, String direction) {
        f.clock.step(60);
        f.handle(f.gateTransaction, f.events.gate(truck, f.gate, direction, "2"));
    }

    @Test
    void tenEntriesAndFourExitsLeaveSixOpenVisits() {
        for (int i = 1; i <= 10; i++) {
            tx("TV-" + i, "IN");
        }
        for (int i = 1; i <= 4; i++) {
            tx("TV-" + i, "OUT");
        }

        assertEquals(10, f.gates.visits.size());
        assertEquals(6, f.gates.open());
    }

    @Test
    void anEntrySetsInAtAndAnExitSetsOutAt() {
        tx("TV-1", "IN");
        Instant in = f.clock.instant();
        tx("TV-1", "OUT");
        Instant out = f.clock.instant();

        var visit = f.gates.find(1, "TV-1", f.gate).orElseThrow();
        assertEquals(in, visit.inAt());
        assertEquals(out, visit.outAt());
        assertEquals("2", visit.lane());
    }

    @Test
    void anExitWithoutAnEntryMakesAVisitWhoseEntryIsUnknown() {
        tx("TV-9", "OUT");

        var visit = f.gates.find(1, "TV-9", f.gate).orElseThrow();
        assertNull(visit.inAt());
        assertEquals(f.clock.instant(), visit.outAt());
        assertEquals(0, f.gates.open());
        var body = f.changes.all.get(0).body();
        assertNull(body.get("inAt"));
    }

    @Test
    void everyChangeIsAStateChangeOfTheVisitWithRisingVersions() {
        tx("TV-1", "IN");
        tx("TV-1", "OUT");

        assertEquals(2, f.changes.all.size());
        assertEquals("gate-visit", f.changes.all.get(0).entityType());
        assertEquals("TV-1:" + f.gate, f.changes.all.get(0).entityId());
        assertEquals(0, f.changes.all.get(0).entityVersion());
        assertEquals(1, f.changes.all.get(1).entityVersion());
    }

    @Test
    void anEntryThatArrivesAfterItsExitFillsTheEntryIn() {
        Instant earlier = f.clock.instant();
        tx("TV-1", "OUT");
        f.handle(f.gateTransaction, f.events.at(f.events.gate("TV-1", f.gate, "IN", null), earlier));

        var visit = f.gates.find(1, "TV-1", f.gate).orElseThrow();
        assertEquals(earlier, visit.inAt());
        assertEquals("2", visit.lane());
        assertEquals(0, f.gates.open());
    }

    @Test
    void anExitBeforeTheEntryIsImpossible() {
        tx("TV-1", "IN");
        var early = f.events.at(f.events.gate("TV-1", f.gate, "OUT", null), ProjectionFixture.T0);
        var e = assertThrows(RejectedEventException.class, () -> f.handle(f.gateTransaction, early));

        assertEquals(DeadLetterReason.INVALID_EVENT, e.reason());
    }

    @Test
    void aTransactionOlderThanTheOneOfTheSameDirectionIsStale() {
        tx("TV-1", "IN");
        var older = f.events.at(f.events.gate("TV-1", f.gate, "IN", null), ProjectionFixture.T0);
        f.handle(f.gateTransaction, older);

        assertEquals(1, f.reconciliation.of(ReconciliationKind.STALE_EVENT).size());
        assertEquals(1, f.changes.all.size());
    }

    @Test
    void aGateThatIsNotAGateIsUnknown() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.gateTransaction, f.events.gate("TV-1", f.berth, "IN", null)));
        assertEquals(DeadLetterReason.UNKNOWN_ENTITY, e.reason());
    }

    @Test
    void aDirectionThatIsNotInOrOutIsInvalid() {
        var e = assertThrows(
                RejectedEventException.class,
                () -> f.handle(f.gateTransaction, f.events.gate("TV-1", f.gate, "SIDEWAYS", null)));
        assertEquals(DeadLetterReason.INVALID_EVENT, e.reason());
    }
}
