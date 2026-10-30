package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A ship's visit. Unknown dimensions, milestones and lastSourceSequence are null. {@code version} is 0 until the first insert. */
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
        Long lastSourceSequence,
        long version) {

    /** A call that carries no source sequence. */
    public VesselCall(
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
            long version) {
        this(
                id,
                portId,
                callCode,
                vesselName,
                imo,
                loaM,
                beamM,
                status,
                eta,
                ata,
                etd,
                atd,
                berthAssetId,
                provenance,
                lastOccurredAt,
                null,
                version);
    }
}
