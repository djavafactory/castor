package com.djavafactory.castor.gateway.mapping.domain;

/**
 * Castor will not guess what a source meant: the record is quarantined with this reason code and the detail. The codes
 * are the quarantine reasons of identity mapping, cargo code mapping and unit checks.
 */
public final class MappingException extends Exception {

    /** No identity mapping row is valid for the source id at the time of the event. */
    public static final String UNKNOWN_MAPPING = "UNKNOWN_MAPPING";

    /** More than one identity mapping row is valid for the source id at the time of the event. */
    public static final String AMBIGUOUS_MAPPING = "AMBIGUOUS_MAPPING";

    /** A unit code that is unknown, given twice, a rate where an amount belongs, or does not fit the cargo family. */
    public static final String MALFORMED_UNIT = "MALFORMED_UNIT";

    /** A source family or commodity code without a cargo code mapping. */
    public static final String UNKNOWN_CARGO_MAPPING = "UNKNOWN_CARGO_MAPPING";

    private final String reasonCode;
    private final String entityType;
    private final String sourceId;

    public MappingException(String reasonCode, String detail) {
        this(reasonCode, detail, null, null);
    }

    /** A failure about one source id of an identity mapping (UNKNOWN_MAPPING, AMBIGUOUS_MAPPING). */
    public MappingException(String reasonCode, String detail, String entityType, String sourceId) {
        super(detail);
        this.reasonCode = reasonCode;
        this.entityType = entityType;
        this.sourceId = sourceId;
    }

    public String reasonCode() {
        return reasonCode;
    }

    /** The source entity type of the id the failure is about, null when it is not about an id. */
    public String entityType() {
        return entityType;
    }

    /** The id the source used, null when the failure is not about an id. */
    public String sourceId() {
        return sourceId;
    }
}
