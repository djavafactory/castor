package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.core.operations.domain.VesselStatus;

/** A vessel call, in the snapshot and as a stream delta. {@code id} is the stream entity id, the call id. */
public record VesselCallDto(
        String id,
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
        Quality quality,
        String source,
        Instant observedAt,
        long freshnessSlaS,
        Instant lastOccurredAt,
        long version) {}
