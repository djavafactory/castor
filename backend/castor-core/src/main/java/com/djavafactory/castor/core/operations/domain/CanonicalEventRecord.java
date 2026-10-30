package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quality;

/** The lineage row of an accepted event: immutable, kept exactly as received. */
public record CanonicalEventRecord(
        UUID eventId,
        long portId,
        String type,
        String aggregateId,
        Instant occurredAt,
        Instant receivedAt,
        Quality quality,
        Map<String, Object> body) {}
