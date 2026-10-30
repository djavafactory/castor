package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The latest state of one machine. status, position, assignment and lastSourceSequence are null when unknown. */
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
        long version) {

    public EquipmentState withStatus(EquipmentStatus status, String reason) {
        return new EquipmentState(
                portId,
                assetId,
                status,
                reason,
                position,
                assignment,
                provenance,
                lastOccurredAt,
                lastSourceSequence,
                version);
    }

    public EquipmentState withPosition(Position position) {
        return new EquipmentState(
                portId,
                assetId,
                status,
                statusReason,
                position,
                assignment,
                provenance,
                lastOccurredAt,
                lastSourceSequence,
                version);
    }

    public EquipmentState withAssignment(Map<String, Object> assignment) {
        return new EquipmentState(
                portId,
                assetId,
                status,
                statusReason,
                position,
                assignment,
                provenance,
                lastOccurredAt,
                lastSourceSequence,
                version);
    }

    /** The same state, now last changed by the event with this provenance, time and source sequence. */
    public EquipmentState changedBy(Provenance provenance, Instant occurredAt, Long sourceSequence) {
        return new EquipmentState(
                portId,
                assetId,
                status,
                statusReason,
                position,
                assignment,
                provenance,
                occurredAt,
                sourceSequence,
                version);
    }
}
