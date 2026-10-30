package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;

/**
 * The stock of one commodity in one storage area: the book balance (from movements and adjustments), the level a sensor
 * last observed next to it, and whether a person has to look at the row. The book quantity is null while no movement or
 * adjustment has been recorded (unknown, never 0), and is never negative or converted between units.
 */
public record StorageInventory(
        long portId,
        UUID storageAssetId,
        String commodity,
        Quantity quantity,
        Instant quantityObservedAt,
        String source,
        Quality quality,
        long version,
        MassBasis massBasis,
        boolean needsReconciliation,
        ObservedLevel level) {

    public StorageInventory {
        if (quantity != null && quantity.value().signum() < 0) {
            throw new NegativeBalanceException("Inventory of " + commodity + " cannot be " + quantity.value());
        }
    }

    public StorageInventory(
            long portId,
            UUID storageAssetId,
            String commodity,
            Quantity quantity,
            Instant quantityObservedAt,
            String source,
            Quality quality,
            long version) {
        this(
                portId,
                storageAssetId,
                commodity,
                quantity,
                quantityObservedAt,
                source,
                quality,
                version,
                null,
                false,
                null);
    }

    /**
     * The inventory after a signed adjustment. Refuses another unit (UnitMismatchException) and a result below zero
     * (NegativeBalanceException). An unknown book balance counts as zero: the book starts with its first change.
     */
    public StorageInventory adjustedBy(Quantity delta, Instant at, String newSource, Quality newQuality) {
        Quantity base = quantity != null ? quantity : new Quantity(java.math.BigDecimal.ZERO, delta.unit());
        Quantity next = base.plus(delta);
        if (next.value().signum() < 0) {
            throw new NegativeBalanceException("Adjusting " + commodity + " by " + delta.value() + " from "
                    + base.value() + " would go below zero");
        }
        return new StorageInventory(
                portId,
                storageAssetId,
                commodity,
                next,
                at,
                newSource,
                newQuality,
                version,
                massBasis,
                needsReconciliation,
                level);
    }

    public StorageInventory withMassBasis(MassBasis basis) {
        return new StorageInventory(
                portId,
                storageAssetId,
                commodity,
                quantity,
                quantityObservedAt,
                source,
                quality,
                version,
                basis,
                needsReconciliation,
                level);
    }

    public StorageInventory withNeedsReconciliation(boolean flag) {
        return new StorageInventory(
                portId,
                storageAssetId,
                commodity,
                quantity,
                quantityObservedAt,
                source,
                quality,
                version,
                massBasis,
                flag,
                level);
    }

    public StorageInventory withLevel(ObservedLevel observed) {
        return new StorageInventory(
                portId,
                storageAssetId,
                commodity,
                quantity,
                quantityObservedAt,
                source,
                quality,
                version,
                massBasis,
                needsReconciliation,
                observed);
    }

    /** The same row with the provenance and time of the last book change. */
    public StorageInventory withProvenance(Instant at, String newSource, Quality newQuality) {
        return new StorageInventory(
                portId,
                storageAssetId,
                commodity,
                quantity,
                at,
                newSource,
                newQuality,
                version,
                massBasis,
                needsReconciliation,
                level);
    }
}
