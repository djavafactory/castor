package com.djavafactory.castor.gateway.normalize.domain;

import java.util.List;

import com.djavafactory.castor.contracts.CanonicalEvent;

/** What the normalizer decided for one inbox record. */
public sealed interface Decision {

    /** The events to put in the outbox, with their final eventIds. */
    record Normalized(List<CanonicalEvent> events) implements Decision {}

    /** The record goes to the quarantine and nothing is published. */
    record Quarantined(String reasonCode, String detail) implements Decision {}
}
