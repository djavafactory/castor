package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.Objects;

import com.djavafactory.castor.contracts.Quality;

/** Where a value came from, how it was obtained and when it was observed: carried by every operational row. */
public record Provenance(String source, Quality quality, Instant observedAt) {

    public Provenance {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
