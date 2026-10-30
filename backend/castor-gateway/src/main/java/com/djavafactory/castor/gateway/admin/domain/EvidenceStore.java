package com.djavafactory.castor.gateway.admin.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** What the gateway can show about what a source sent and what became of it. */
public interface EvidenceStore {

    /**
     * One source of the port with its counters.
     *
     * @param accepted records received in the window (replays are not received, so they are not counted)
     * @param duplicate repeats answered "duplicate" in the window
     * @param quarantined records of the window that were quarantined
     * @param lastReceivedAt null when the source never sent a record
     * @param freshnessSlaSeconds the longest age, in seconds, the source may have before it is STALE
     */
    record SourceStatus(
            String code,
            boolean enabled,
            boolean requireSignature,
            String qualityDefault,
            int activeKeys,
            long accepted,
            long duplicate,
            long quarantined,
            Instant lastReceivedAt,
            Freshness freshness,
            long freshnessSlaSeconds) {}

    /**
     * The raw numbers of a source before freshness is decided.
     *
     * @param freshnessSlaEntries the ISO-8601 durations of the source's per-event-type SLA, empty when none is set
     */
    record SourceCounters(
            String code,
            boolean enabled,
            boolean requireSignature,
            String qualityDefault,
            int activeKeys,
            long accepted,
            long duplicate,
            long quarantined,
            Instant lastReceivedAt,
            List<String> freshnessSlaEntries) {}

    /**
     * One quarantine entry.
     *
     * @param id the entry id, which orders the list
     * @param replayOf the original record when this entry is the quarantine of a replay, else null
     * @param replays how many replays the original has had
     * @param latestReplayStatus the inbox status of the latest replay, null when there is none
     * @param subjectType the source entity type a mapping failure is about, else null
     * @param subjectId the id the source used, else null
     */
    record QuarantineEntry(
            long id,
            UUID receptionId,
            String source,
            String sourceEventId,
            String reasonCode,
            String detail,
            Instant receivedAt,
            Instant quarantinedAt,
            UUID replayOf,
            int replays,
            String latestReplayStatus,
            String subjectType,
            String subjectId) {}

    /**
     * One replay of a quarantined record.
     *
     * @param status RECEIVED (waiting for the normalizer), NORMALIZED or QUARANTINED
     * @param reasonCode why it was quarantined again, null otherwise
     * @param events canonical events the replay produced
     * @param published how many of them the relay has published
     */
    record ReplayAttempt(
            UUID receptionId,
            Instant receivedAt,
            String status,
            String reasonCode,
            String detail,
            int events,
            int published) {}

    /**
     * The entry with the payload as stored.
     *
     * @param raw null when the payload was emptied by housekeeping
     * @param attempts every replay of the original, oldest first
     */
    record QuarantineView(QuarantineEntry entry, JsonNode raw, List<ReplayAttempt> attempts) {}

    /**
     * @param source only this source, null for all
     * @param reason only this reason code, null for all
     * @param before only entries with an id below this one (the keyset of the previous page), null for the first page
     */
    record QuarantineFilter(String source, String reason, Long before, int limit) {}

    /** What became of a request to replay. */
    sealed interface Replayed {

        /** A new inbox row was written. */
        record Created(UUID receptionId, UUID replayOf) implements Replayed {}

        /** Nothing in the quarantine of the port has this reception id. */
        record NotQuarantined() implements Replayed {}

        /** The raw payload was emptied by housekeeping: there is nothing left to replay. */
        record RawExpired() implements Replayed {}

        /** An earlier replay of the same record has not been normalized yet. */
        record Pending(UUID receptionId) implements Replayed {}
    }

    List<SourceCounters> sources(String portId, Instant since);

    List<QuarantineEntry> quarantine(String portId, QuarantineFilter filter);

    Optional<QuarantineView> quarantined(String portId, UUID receptionId);

    /**
     * In one transaction: checks the record is in the quarantine with its payload and has no replay waiting, and writes a
     * new inbox row (status RECEIVED, received at {@code now}) with the payload and sourceEventId of the record and
     * {@code replay_of} set to the original. Nothing that exists is changed.
     */
    Replayed replay(String portId, UUID receptionId, UUID newReceptionId, Instant now);
}
