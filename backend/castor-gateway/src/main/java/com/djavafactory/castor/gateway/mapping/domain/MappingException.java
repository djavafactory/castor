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

    public MappingException(String reasonCode, String detail) {
        super(detail);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
