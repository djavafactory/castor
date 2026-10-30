package com.djavafactory.castor.core.operations.domain;

/** Why an event was set aside instead of applied. Stored as the first word of ops.dead_letter.error. */
public enum DeadLetterReason {
    /** Unreadable, not a valid envelope or payload, or a field that cannot be applied. Retrying cannot help. */
    INVALID_EVENT,
    UNKNOWN_TYPE,
    UNKNOWN_PORT,
    /** A movement in another unit than its lot, or wet and dry mass in one balance: units are never converted (R4). */
    UNIT_MISMATCH,
    /** The asset, berth, call, lot or cargo family the event names is not known. */
    UNKNOWN_ENTITY,
    OUT_OF_EXTENT,
    /** The handler kept failing for the allowed number of attempts. */
    PROCESSING_FAILED
}
