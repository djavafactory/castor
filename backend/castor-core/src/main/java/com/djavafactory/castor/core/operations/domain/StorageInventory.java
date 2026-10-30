package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;

/** The stock of one commodity in one storage area. Never negative; never converted between units. */
public record StorageInventory(
        long portId,
        UUID storageAssetId,
        String commodity,
        Quantity quantity,
        Instant quantityObservedAt,
        String source,
        Quality quality,
        long version) {

    public StorageInventory {
        if (quantity.value().signum() < 0) {
            throw new NegativeBalanceException("Inventory of " + commodity + " cannot be " + quantity.value());
        }
    }

    /**
     * The inventory after a signed adjustment. Refuses another unit (UnitMismatchException) and a result below zero
     * (NegativeBalanceException).
     */
    public StorageInventory adjustedBy(Quantity delta, Instant at, String newSource, Quality newQuality) {
        Quantity next = quantity.plus(delta);
        if (next.value().signum() < 0) {
            throw new NegativeBalanceException("Adjusting " + commodity + " by " + delta.value() + " from "
                    + quantity.value() + " would go below zero");
        }
        return new StorageInventory(portId, storageAssetId, commodity, next, at, newSource, newQuality, version);
    }
}
