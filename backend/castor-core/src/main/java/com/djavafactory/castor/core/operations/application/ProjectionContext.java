package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.operations.domain.Provenance;

/** What a handler needs to apply one event: the port it belongs to and the event itself. */
public record ProjectionContext(long portId, UUID eventId, CanonicalEvent event) {

    /** The time the event happened, cut to the microseconds the database keeps, so a change body equals a re-read row. */
    public Instant occurredAt() {
        return event.occurredAt().truncatedTo(ChronoUnit.MICROS);
    }

    public Provenance provenance() {
        return new Provenance(event.sourceSystem(), event.quality(), occurredAt());
    }
}
