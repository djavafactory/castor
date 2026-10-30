package com.djavafactory.castor.gateway.mapping.domain;

import java.time.Instant;

/**
 * One mapping row: the canonical id a source id stands for from {@code effectiveFrom} (included) to {@code effectiveTo}
 * (excluded, null while it is current).
 */
public record MappingRow(String canonicalId, Instant effectiveFrom, Instant effectiveTo) {

    public boolean validAt(Instant at) {
        return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
}
