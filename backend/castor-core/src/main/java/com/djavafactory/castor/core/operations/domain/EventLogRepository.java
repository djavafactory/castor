package com.djavafactory.castor.core.operations.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface EventLogRepository {

    /** Stores the event; false when the event id is already stored (a replay). The row is never changed after. */
    boolean append(CanonicalEventRecord event);

    Optional<CanonicalEventRecord> find(UUID eventId);

    /** Marks the event as handled by the consumer; false when it already was (skip it). */
    boolean markProcessed(long portId, String consumer, UUID eventId);

    /** The type code of each stored event among the ids; ids that are not stored are absent. */
    Map<UUID, String> types(Collection<UUID> eventIds);

    boolean isProcessed(String consumer, UUID eventId);
}
