package it.replica8.castor.core.shared.domain;

/** A write needs an If-Match header and none was sent. Reported as HTTP 412. */
public class MissingIfMatchException extends RuntimeException {

    public MissingIfMatchException() {
        super("The If-Match header is required");
    }
}
