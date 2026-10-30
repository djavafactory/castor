package com.djavafactory.castor.core.operations.domain;

/** The event can never be applied as it is: it goes to the dead letters at once, without retries. */
public class RejectedEventException extends RuntimeException {

    private final DeadLetterReason reason;

    public RejectedEventException(DeadLetterReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DeadLetterReason reason() {
        return reason;
    }
}
