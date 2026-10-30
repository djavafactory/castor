package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A ship's visit. Unknown dimensions and milestones are null. {@code version} is 0 until the first insert. */
public record VesselCall(
        UUID id,
        long portId,
        String callCode,
        String vesselName,
        String imo,
        BigDecimal loaM,
        BigDecimal beamM,
        VesselStatus status,
        Instant eta,
        Instant ata,
        Instant etd,
        Instant atd,
        UUID berthAssetId,
        Provenance provenance,
        Instant lastOccurredAt,
        long version) {}
