package com.djavafactory.castor.core.operations.application;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EquipmentAssignmentHandlerTest {

    private final ProjectionFixture f = new ProjectionFixture();

    @Test
    void storesWhatTheMachineIsAssignedTo() {
        f.handle(f.assignment, f.events.assignment(f.crane, "JOB-7", "MV-AURORA-0412"));

        assertEquals(
                Map.of("jobId", "JOB-7", "vesselCallId", "MV-AURORA-0412"),
                f.equipment.states.get(f.crane).assignment());
        assertEquals("equipment", f.changes.all.get(0).entityType());
    }

    @Test
    void theEventIsTheWholeAssignmentSoAnUnnamedTargetIsDropped() {
        f.handle(f.assignment, f.events.assignment(f.crane, "JOB-7", "MV-AURORA-0412"));
        f.clock.step(10);
        f.handle(f.assignment, f.events.assignment(f.crane, "JOB-8", null));

        assertEquals(Map.of("jobId", "JOB-8"), f.equipment.states.get(f.crane).assignment());
    }

    @Test
    void anEventWithNoTargetUnassignsTheMachine() {
        f.handle(f.assignment, f.events.assignment(f.crane, "JOB-7", null));
        f.clock.step(10);
        f.handle(f.assignment, f.events.assignment(f.crane, null, null));

        assertNull(f.equipment.states.get(f.crane).assignment());
        assertNull(f.changes.all.get(1).body().get("assignment"));
    }

    @Test
    void anOlderAssignmentIsKeptAsEvidenceOnly() {
        f.clock.step(60);
        f.handle(f.assignment, f.events.assignment(f.crane, "JOB-new", null));
        f.handle(f.assignment, f.events.at(f.events.assignment(f.crane, "JOB-old", null), ProjectionFixture.T0));

        assertEquals(Map.of("jobId", "JOB-new"), f.equipment.states.get(f.crane).assignment());
        assertEquals(1, f.reconciliation.items.size());
    }
}
