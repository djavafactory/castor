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

    /**
     * What the rejection is about, when it is one thing: the source entity type and the id the source used ("BERTH",
     * "B9" for an unmapped berth). It lets the quarantine be counted per source id.
     */
    public record Subject(String type, String id) {}

    private final String reasonCode;
    private final Subject subject;

    public Rejection(String reasonCode, String detail) {
        this(reasonCode, detail, null);
    }

    public Rejection(String reasonCode, String detail, Subject subject) {
        super(detail);
        this.reasonCode = reasonCode;
        this.subject = subject;
    }

    /** The subject of the rejection, null when it has none. */
    public Subject subject() {
        return subject;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
