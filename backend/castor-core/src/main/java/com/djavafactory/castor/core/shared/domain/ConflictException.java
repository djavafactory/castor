package com.djavafactory.castor.core.shared.domain;

/** The request clashes with the current state of a resource. Reported as HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
