package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;

/**
 * The latest state of one machine, as the snapshot lists it and as a stream delta carries it (R2, R3). {@code id} is
 * the stream entity id, the asset id. Position, assignment and the other optional values are null when unknown.
 */
public record EquipmentStateDto(
        String id,
        UUID assetId,
        EquipmentStatus status,
        String statusReason,
        Position position,
        Map<String, Object> assignment,
        Quality quality,
        String source,
        Instant observedAt,
        long freshnessSlaS,
        Instant lastOccurredAt,
        long version) {

    /** A WGS84 fix and when it was observed. */
    public record Position(double lon, double lat, BigDecimal headingDeg, BigDecimal accuracyM, Instant observedAt) {}
}
