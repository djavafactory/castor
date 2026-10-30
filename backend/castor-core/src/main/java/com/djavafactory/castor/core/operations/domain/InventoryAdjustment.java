package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quantity;

/** Immutable: a signed change of stock; the delta is negative to remove stock. */
public record InventoryAdjustment(
        long portId,
        UUID storageAssetId,
        String commodity,
        Quantity delta,
        String reason,
        UUID eventId,
        Instant occurredAt) {}
