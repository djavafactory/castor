package com.djavafactory.castor.gateway.normalize.domain;

import java.util.List;

import com.djavafactory.castor.contracts.CanonicalEvent;

/** What the normalizer decided for one inbox record. */
public sealed interface Decision {

    /** The events to put in the outbox, with their final eventIds. */
    record Normalized(List<CanonicalEvent> events) implements Decision {}

    /**
     * The record goes to the quarantine and nothing is published.
     *
     * @param subject what the failure was about, null when it is not about one source id
     */
    record Quarantined(String reasonCode, String detail, Rejection.Subject subject) implements Decision {

        public Quarantined(String reasonCode, String detail) {
            this(reasonCode, detail, null);
        }
    }
}
