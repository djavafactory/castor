package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quantity;

/** Immutable: one lot moved between two places. eventId makes a replayed event a no-op. */
public record CargoMovement(
        long portId,
        UUID lotId,
        UUID fromId,
        UUID toId,
        Quantity quantity,
        UUID handledBy,
        Instant occurredAt,
        UUID eventId) {

    public CargoMovement {
        if (quantity.value().signum() <= 0) {
            throw new IllegalArgumentException("A movement moves a positive quantity");
        }
    }
}
