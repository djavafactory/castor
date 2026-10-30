package com.djavafactory.castor.core.operations.api;

import java.time.Instant;
import java.util.List;

/**
 * One consistent picture of the port. {@code sequence} is the stream cursor at the time of reading: the deltas after it
 * are what the snapshot does not contain yet. The two open counts are numbers, not lists.
 */
public record SnapshotDto(
        long sequence,
        Instant serverTime,
        double clockSpeed,
        List<EquipmentStateDto> equipment,
        List<VesselCallDto> vesselCalls,
        List<InventoryDto> inventories,
        long openGateVisits,
        long openReconciliationItems) {}
