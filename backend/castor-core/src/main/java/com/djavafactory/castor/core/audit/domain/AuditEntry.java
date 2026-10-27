package com.djavafactory.castor.core.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the audit trail. before and after are JSON text: the asset revision as a GeoJSON feature, or the version
 * row; null where there was nothing before or nothing after.
 */
public record AuditEntry(
        long id,
        Instant at,
        String actor,
        AuditAction action,
        String entityKind,
        String entityId,
        UUID layoutVersionId,
        String before,
        String after,
        String requestId) {}
