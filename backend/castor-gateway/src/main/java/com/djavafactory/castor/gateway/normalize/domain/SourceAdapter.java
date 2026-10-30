package com.djavafactory.castor.gateway.normalize.domain;

import java.util.List;

import com.djavafactory.castor.contracts.CanonicalEvent;

/**
 * Turns the records of one source into canonical events. A CDI bean implementing this interface is picked up by the
 * normalizer; INGEST-03 adds one per Westports system. An adapter keeps no state between records and does not decide
 * {@code eventId}: whatever it puts there is replaced by {@link EventIds#of} so a retried normalization gives the same
 * ids.
 */
public interface SourceAdapter {

    /** The source this adapter reads, as in {@code Source.code()} ("canonical", "itap", ...). */
    String source();

    /**
     * @return the events of the record, at least one unless the record carries nothing to publish
     * @throws Rejection the record is wrong; it goes to the quarantine
     */
    List<CanonicalEvent> normalize(InboxRecord record) throws Rejection;
}
