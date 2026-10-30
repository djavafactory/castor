package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;

/** A truck at a gate. inAt and outAt are null until that transaction is seen. */
public record GateVisit(
        long portId,
        String truckVisitId,
        UUID gateAssetId,
        Instant inAt,
        Instant outAt,
        String lane,
        String source,
        Quality quality) {}
