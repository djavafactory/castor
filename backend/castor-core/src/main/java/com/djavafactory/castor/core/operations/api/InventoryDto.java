package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Unit;

/**
 * The stock of one commodity in one storage area, in the snapshot and as a stream delta. {@code id} is the stream
 * entity id: {@code <storageAssetId>:<commodity>}. {@code observedAt} is when the quantity was observed.
 */
public record InventoryDto(
        String id,
        UUID storageAssetId,
        String commodity,
        BigDecimal quantity,
        Unit unit,
        Quality quality,
        String source,
        Instant observedAt,
        long freshnessSlaS,
        long version) {}
