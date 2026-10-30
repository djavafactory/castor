package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;

/** One stretch of a status; endedAt is null while it lasts. */
public record EquipmentStatusInterval(
        long portId,
        UUID assetId,
        EquipmentStatus status,
        String reason,
        Instant startedAt,
        Instant endedAt,
        String source,
        Quality quality) {}
