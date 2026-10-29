package com.djavafactory.castor.contracts;

/** An event could not be encoded or decoded. */
public class CanonicalEventException extends RuntimeException {

    public CanonicalEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
