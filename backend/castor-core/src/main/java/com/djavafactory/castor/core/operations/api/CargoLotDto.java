package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.MassBasis;

/**
 * A cargo lot as a stream delta ({@code cargo-lot}). {@code id} is the lot id; {@code currentLocationId} and
 * {@code observedAt} (the time of the movement that put it there) are null until the lot has moved.
 */
public record CargoLotDto(
        String id,
        String lotCode,
        String consignmentCode,
        String cargoFamily,
        String commodity,
        Unit unit,
        BigDecimal registeredQuantity,
        MassBasis massBasis,
        UUID currentLocationId,
        Quality quality,
        String source,
        Instant observedAt,
        long freshnessSlaS,
        long version) {}
