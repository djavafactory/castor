package com.djavafactory.castor.core.operations.domain;

/** Why an event was set aside instead of applied. Stored as the first word of ops.dead_letter.error. */
public enum DeadLetterReason {
    /** Unreadable, not a valid envelope or payload, or a field that cannot be applied. Retrying cannot help. */
    INVALID_EVENT,
    UNKNOWN_TYPE,
    UNKNOWN_PORT,
    /** The asset, berth or call the event names is not in the published layout. */
    UNKNOWN_ENTITY,
    OUT_OF_EXTENT,
    /** The handler kept failing for the allowed number of attempts. */
    PROCESSING_FAILED
}
