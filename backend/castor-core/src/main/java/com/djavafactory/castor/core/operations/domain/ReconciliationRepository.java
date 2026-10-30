package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReconciliationRepository {

    /** Stores the item; a null createdAt is stamped with the database time. */
    ReconciliationItem open(ReconciliationItem item);

    Optional<ReconciliationItem> find(long id);

    /** Newest first; a null kind lists every kind. */
    List<ReconciliationItem> findByStatus(long portId, ReconciliationStatus status, ReconciliationKind kind, int limit);

    /** Newest first. */
    default List<ReconciliationItem> findByStatus(long portId, ReconciliationStatus status, int limit) {
        return findByStatus(portId, status, null, limit);
    }

    /** True when an open item of the kind already names this entity and field in its detail. */
    boolean hasOpen(long portId, ReconciliationKind kind, String entityType, String entityId, String field);

    /** Resolves an open item; false when it is unknown or already resolved. */
    boolean resolve(long id, String resolvedBy, String note, Instant at);
}
