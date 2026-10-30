package com.djavafactory.castor.core.operations.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;

/**
 * An item for a person to look at. {@code entityType} and {@code entityId} use the stream spelling (inventory,
 * vessel-call, cargo-lot, gate-visit, equipment); {@code eventId} and {@code eventType} name the event that opened it;
 * {@code detail} is what the rule saw. {@code createdAt} lets the reader work out the age against the server clock.
 */
public record ReconciliationItemDto(
        long id,
        ReconciliationKind kind,
        String entityType,
        String entityId,
        UUID eventId,
        String eventType,
        Map<String, Object> detail,
        ReconciliationStatus status,
        Instant createdAt,
        String resolvedBy,
        Instant resolvedAt,
        String note) {}
