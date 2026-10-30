package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The latest state of one machine. position, assignment and lastSourceSequence are null when unknown. */
public record EquipmentState(
        long portId,
        UUID assetId,
        EquipmentStatus status,
        String statusReason,
        Position position,
        Map<String, Object> assignment,
        Provenance provenance,
        Instant lastOccurredAt,
        Long lastSourceSequence,
        long version) {}
