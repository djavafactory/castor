package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something a person must look at. id is null until stored; createdAt is null on an item to be stored now, and the
 * repository then stamps it. resolvedAt is null while the item is open.
 */
public record ReconciliationItem(
        Long id,
        long portId,
        ReconciliationKind kind,
        String entityType,
        String entityId,
        UUID eventId,
        Map<String, Object> detail,
        ReconciliationStatus status,
        String resolvedBy,
        String note,
        Instant createdAt,
        Instant resolvedAt) {

    public ReconciliationItem(
            Long id,
            long portId,
            ReconciliationKind kind,
            String entityType,
            String entityId,
            UUID eventId,
            Map<String, Object> detail,
            ReconciliationStatus status,
            String resolvedBy,
            String note) {
        this(id, portId, kind, entityType, entityId, eventId, detail, status, resolvedBy, note, null, null);
    }
}
