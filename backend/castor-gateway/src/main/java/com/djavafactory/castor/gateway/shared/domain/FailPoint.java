package com.djavafactory.castor.gateway.shared.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Places in the pipeline where a test (or a developer on the dev stack) can make the gateway "stop", to prove that an
 * event answered 202 is never lost and never published twice. Armed only in local, dev and test.
 */
public enum FailPoint {
    /** A record is in the inbox, nothing has been normalized yet. */
    BEFORE_NORMALIZE("before-normalize"),
    /** The event is in the outbox, nothing has been sent to Kafka yet. */
    BEFORE_RELAY("before-relay"),
    /** Kafka acknowledged the event, but the outbox row is not yet marked published. */
    AFTER_SEND_BEFORE_MARK("after-send-before-mark");

    private final String code;

    FailPoint(String code) {
        this.code = code;
    }

    /** The value of {@code castor.fail-point} (CASTOR_FAIL_POINT). */
    public String code() {
        return code;
    }

    public static Optional<FailPoint> fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
