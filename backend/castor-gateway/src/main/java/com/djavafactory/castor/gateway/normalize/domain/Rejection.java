package com.djavafactory.castor.gateway.normalize.domain;

/**
 * An adapter refuses a record: it is quarantined, with this reason code and detail, and nothing is published. A record
 * the source got wrong is a rejection; a bug in the adapter is not (the normalizer quarantines that as ADAPTER_ERROR).
 */
public final class Rejection extends Exception {

    /** The record is not a valid canonical event (envelope or payload schema). */
    public static final String SCHEMA_INVALID = "SCHEMA_INVALID";

    /** The record names another port than the one it was sent to. */
    public static final String PORT_MISMATCH = "PORT_MISMATCH";

    /** No adapter exists for the source of the record. */
    public static final String NO_ADAPTER = "NO_ADAPTER";

    /** The adapter failed with an error of its own. */
    public static final String ADAPTER_ERROR = "ADAPTER_ERROR";

    private final String reasonCode;

    public Rejection(String reasonCode, String detail) {
        super(detail);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
