package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Unit;

/** A lot of cargo. registeredQuantity, massBasis and the location are null while unknown. */
public record CargoLot(
        UUID id,
        long portId,
        String lotCode,
        String consignmentCode,
        String cargoFamily,
        String commodity,
        Unit unit,
        BigDecimal registeredQuantity,
        MassBasis massBasis,
        UUID currentLocationId,
        Instant locationOccurredAt,
        String source,
        Quality quality,
        long version) {}
