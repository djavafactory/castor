package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every value a source reported for a precedence-ruled field of a call, applied or not (the history of a conflict). */
public interface VesselCallValueRepository {

    /** One reported value. {@code source} is lower case; {@code value} is text (an instant, a status or an asset id). */
    record Value(String field, String source, String value, Instant occurredAt, Long sourceSequence) {}

    void append(long portId, UUID callId, UUID eventId, Value value);

    /** The latest value of every (field, source) of the call. */
    List<Value> latestBySource(UUID callId);

    /** The latest value any field of the call got from the source, whatever the field: that source's own clock. */
    Optional<Value> lastFrom(UUID callId, String source);

    /** Every reported value of one field, oldest first. */
    List<Value> history(UUID callId, String field);
}
