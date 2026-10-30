package com.djavafactory.castor.core.audit.domain;

import java.util.List;
import java.util.UUID;

/** Storage of the audit trail. Appends join the transaction of the caller. */
public interface AuditRepository {

    /** Appends a row; the port is that of the layout version. */
    void append(
            String actor,
            AuditAction action,
            String entityKind,
            String entityId,
            UUID layoutVersionId,
            String before,
            String after,
            String requestId);

    /** Appends a row of a change that belongs to the port, not to a layout version (integrations). */
    void appendForPort(
            String portCode,
            String actor,
            AuditAction action,
            String entityKind,
            String entityId,
            String before,
            String after,
            String requestId);

    /** Newest first by id; the filter's before is an exclusive id bound. */
    List<AuditEntry> find(String portCode, AuditFilter filter);
}
