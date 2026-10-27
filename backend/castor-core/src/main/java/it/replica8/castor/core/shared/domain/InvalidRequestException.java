package it.replica8.castor.core.shared.domain;

/** A request parameter is malformed. Reported as HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
