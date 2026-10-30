package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface StateChangeRepository {

    /**
     * Appends the change with the next sequence of the port and returns it. Takes the row lock of the port's cursor
     * until the end of the caller's transaction, so concurrent writers queue up and a rollback leaves no gap.
     */
    StateChange append(
            long portId,
            String entityType,
            String entityId,
            long entityVersion,
            Instant occurredAt,
            Map<String, Object> body);

    /** Changes after the sequence, oldest first. */
    List<StateChange> after(long portId, long sequence, int limit);

    /** Changes not yet published, oldest first. */
    List<StateChange> unpublished(long portId, int limit);

    void markPublished(long portId, long upToSequence, Instant at);

    /** The last sequence handed out; 0 when none. */
    long lastSequence(long portId);
}
