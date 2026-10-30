package com.djavafactory.castor.core.operations.api;

import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;

/**
 * A truck at a gate, as a stream delta (the snapshot only counts the open ones). {@code id} is the stream entity id:
 * {@code <truckVisitId>:<gateAssetId>}. {@code observedAt} is the last transaction seen, the exit or else the entry.
 */
public record GateVisitDto(
        String id,
        String truckVisitId,
        UUID gateAssetId,
        Instant inAt,
        Instant outAt,
        String lane,
        Quality quality,
        String source,
        Instant observedAt,
        long freshnessSlaS) {}
