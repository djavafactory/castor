package com.djavafactory.castor.core.operations.domain;

import java.util.Map;
import java.util.UUID;

/** Something a person must look at. id is null until stored. */
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
        String note) {}
