package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.MassBasis;

/**
 * The stock of one commodity in one storage area, in the snapshot and as a stream delta. {@code id} is the stream
 * entity id: {@code <storageAssetId>:<commodity>}. {@code quantity} and {@code unit} are the book balance (null while no
 * movement was recorded), {@code observedAt} is when it last changed. The measured level, the usable capacity and the
 * masses are separate on purpose: each is null or UNKNOWN when it cannot be known (R3), and wet and dry tonnes, or
 * volumes and tonnes, are never added together (R4).
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
        long version,
        MassBasis massBasis,
        boolean needsReconciliation,
        Capacity capacity,
        Observed observed,
        Mass bookMass,
        Mass observedMass) {

    /** The usable capacity of the asset in the published layout. {@code status} is KNOWN or UNKNOWN. */
    public record Capacity(String status, BigDecimal value, Unit unit) {

        public static Capacity unknown() {
            return new Capacity("UNKNOWN", null, null);
        }
    }

    /** What a tank or silo sensor last read; density and temperature are those of the event. */
    public record Observed(
            BigDecimal quantity,
            Unit unit,
            Instant observedAt,
            String source,
            Quality quality,
            BigDecimal densityKgM3,
            BigDecimal temperatureC) {}

    /** A mass in tonnes. {@code status} is KNOWN or UNKNOWN; UNKNOWN has no value (a volume needs a density). */
    public record Mass(String status, BigDecimal tonnes) {

        public static Mass of(BigDecimal tonnes) {
            return tonnes == null ? new Mass("UNKNOWN", null) : new Mass("KNOWN", tonnes);
        }
    }
}
