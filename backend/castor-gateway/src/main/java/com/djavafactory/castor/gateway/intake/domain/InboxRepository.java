package com.djavafactory.castor.gateway.intake.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The durable inbox: what has been accepted, committed to the database before the sender is answered. */
public interface InboxRepository {

    /**
     * One accepted record.
     *
     * @param rawJson the record as received, to be stored as jsonb
     */
    record NewRecord(UUID receptionId, String sourceEventId, Instant receivedAt, String rawJson) {}

    /** What became of a record. */
    sealed interface Stored {

        /** A new row, status RECEIVED. */
        record Accepted(UUID receptionId) implements Stored {}

        /** The source sent this sourceEventId before; the id is the one of the first row. */
        record Duplicate(UUID receptionId) implements Stored {}

        /** The database cannot hold the record's JSON (for example a NUL character in a string). */
        record Unstorable(String reason) implements Stored {}
    }

    /**
     * Inserts the records in one transaction and commits before returning, one answer per record in the same order.
     * A (port, source, sourceEventId) that exists already, or appears twice in the call, answers {@link Stored.Duplicate}
     * with the receptionId of the first.
     */
    List<Stored> store(String portId, Source source, List<NewRecord> records);
}
