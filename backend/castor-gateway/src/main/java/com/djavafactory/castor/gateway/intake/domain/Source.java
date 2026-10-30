package com.djavafactory.castor.gateway.intake.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The integration sources a port can have (the {@code code} column of ingest.integration_source). {@code canonical}
 * takes events that are already canonical (the e2e helper and the emulator's own events); the others are the Westports
 * systems, each with an adapter from INGEST-03.
 */
public enum Source {
    CANONICAL("canonical"),
    CBAS("cbas"),
    ITAP("itap"),
    SCSS("scss"),
    EDIC("edic"),
    SENSORS("sensors");

    private final String code;

    Source(String code) {
        this.code = code;
    }

    /** The lower-case code used in URLs, the database and {@code SourceAdapter.source()}. */
    public String code() {
        return code;
    }

    public static Optional<Source> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
