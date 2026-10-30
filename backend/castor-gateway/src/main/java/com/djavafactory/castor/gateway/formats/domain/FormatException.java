package com.djavafactory.castor.gateway.formats.domain;

/** A file or message is not in the format its source promised. */
public final class FormatException extends Exception {

    public FormatException(String message) {
        super(message);
    }
}
