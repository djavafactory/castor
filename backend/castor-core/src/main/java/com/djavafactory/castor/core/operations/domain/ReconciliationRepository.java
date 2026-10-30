package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReconciliationRepository {

    ReconciliationItem open(ReconciliationItem item);

    Optional<ReconciliationItem> find(long id);

    /** Newest first. */
    List<ReconciliationItem> findByStatus(long portId, ReconciliationStatus status, int limit);

    /** Resolves an open item; false when it is unknown or already resolved. */
    boolean resolve(long id, String resolvedBy, String note, Instant at);
}
