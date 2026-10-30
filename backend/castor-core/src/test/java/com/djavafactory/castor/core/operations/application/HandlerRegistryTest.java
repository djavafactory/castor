package com.djavafactory.castor.core.operations.application;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.EventType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerRegistryTest {

    private final ProjectionFixture f = new ProjectionFixture();

    @Test
    void findsTheHandlerOfAType() {
        var registry = new HandlerRegistry(List.of(f.status, f.position, f.assignment, f.vesselCall));

        assertSame(f.position, registry.find("equipment.position.observed").orElseThrow());
        assertSame(f.vesselCall, registry.find("vessel.call.updated").orElseThrow());
    }

    @Test
    void aTypeWithoutAHandlerIsNotFoundSoTheEventIsUnknown() {
        var registry = new HandlerRegistry(List.of(f.status));

        assertTrue(registry.find(EventType.CARGO_LOT_REGISTERED.code()).isEmpty());
        assertTrue(registry.find("made.up").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void twoHandlersForOneTypeStopTheStart() {
        var e = assertThrows(IllegalStateException.class, () -> new HandlerRegistry(List.of(f.status, f.status)));
        assertEquals("Two handlers for equipment.status.changed", e.getMessage());
    }

    @Test
    void everyHandlerNamesItsOwnType() {
        assertEquals(EventType.EQUIPMENT_STATUS_CHANGED, f.status.type());
        assertEquals(EventType.EQUIPMENT_POSITION_OBSERVED, f.position.type());
        assertEquals(EventType.EQUIPMENT_ASSIGNMENT_CHANGED, f.assignment.type());
        assertEquals(EventType.VESSEL_CALL_UPDATED, f.vesselCall.type());
    }
}
