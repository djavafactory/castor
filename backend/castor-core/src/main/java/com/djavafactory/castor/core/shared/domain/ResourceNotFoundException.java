package com.djavafactory.castor.core.shared.domain;

/** The requested resource does not exist. Reported as HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
