package com.djavafactory.castor.gateway.admin.application;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.gateway.admin.domain.AdminException;
import com.djavafactory.castor.gateway.admin.domain.EvidenceStore;
import com.djavafactory.castor.gateway.admin.domain.EvidenceStore.QuarantineEntry;
import com.djavafactory.castor.gateway.admin.domain.EvidenceStore.ReplayAttempt;
import com.djavafactory.castor.gateway.admin.domain.EvidenceStore.SourceCounters;
import com.djavafactory.castor.gateway.admin.domain.EvidenceStore.SourceStatus;
import com.djavafactory.castor.gateway.admin.domain.Freshness;
import com.djavafactory.castor.gateway.admin.domain.PortId;
import com.djavafactory.castor.gateway.admin.domain.Redaction;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * What an operator may read: the sources with their counters of the last 24 hours, the quarantine and one quarantined
 * record. The payload and the texts of a record leave here with keys and secrets masked; the stored row is untouched.
 */
@ApplicationScoped
public class ReadEvidence {

    /** The window of the counters. */
    public static final Duration WINDOW = Duration.ofHours(24);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** A page of the quarantine, newest first; {@code nextBefore} is the id to pass for the next page, null at the end. */
    public record QuarantinePage(List<QuarantineEntry> items, Long nextBefore) {}

    /**
     * @param raw the payload with secrets masked; null when housekeeping emptied it ({@code rawAvailable} is false)
     * @param attempts every replay of the original record, oldest first
     */
    public record QuarantineDetail(
            QuarantineEntry entry, JsonNode raw, boolean rawAvailable, List<ReplayAttempt> attempts) {}

    private final EvidenceStore store;
    private final InstantSource clock;
    private final Duration defaultSla;

    public ReadEvidence(EvidenceStore store, InstantSource clock, CastorConfig castor) {
        this.store = store;
        this.clock = clock;
        this.defaultSla = castor.admin().sourceFreshness();
    }

    public List<SourceStatus> sources(String portId) {
        Instant now = clock.instant();
        return store.sources(PortId.require(portId), now.minus(WINDOW)).stream()
                .map(counters -> status(counters, now))
                .toList();
    }

    public QuarantinePage quarantine(String portId, String source, String reason, Long before, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw AdminException.invalid("INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT);
        }
        String sourceCode = blank(source) == null ? null : source(source).code();
        List<QuarantineEntry> entries = store
                .quarantine(
                        PortId.require(portId),
                        new EvidenceStore.QuarantineFilter(sourceCode, blank(reason), before, size))
                .stream()
                .map(ReadEvidence::masked)
                .toList();
        return new QuarantinePage(
                entries,
                entries.size() == size ? entries.get(entries.size() - 1).id() : null);
    }

    public QuarantineDetail detail(String portId, UUID receptionId) {
        EvidenceStore.QuarantineView view = store.quarantined(PortId.require(portId), receptionId)
                .orElseThrow(
                        () -> AdminException.notFound("NOT_IN_QUARANTINE", "No quarantined record " + receptionId));
        List<ReplayAttempt> attempts = view.attempts().stream()
                .map(a -> new ReplayAttempt(
                        a.receptionId(),
                        a.receivedAt(),
                        a.status(),
                        a.reasonCode(),
                        Redaction.text(a.detail()),
                        a.events(),
                        a.published()))
                .toList();
        return new QuarantineDetail(masked(view.entry()), Redaction.redact(view.raw()), view.raw() != null, attempts);
    }

    private SourceStatus status(SourceCounters c, Instant now) {
        Duration sla = c.freshnessSlaEntries().stream()
                .map(ReadEvidence::duration)
                .max(Duration::compareTo)
                .orElse(defaultSla);
        return new SourceStatus(
                c.code(),
                c.enabled(),
                c.requireSignature(),
                c.qualityDefault(),
                c.activeKeys(),
                c.accepted(),
                c.duplicate(),
                c.quarantined(),
                c.lastReceivedAt(),
                Freshness.of(c.lastReceivedAt(), now, sla),
                sla.toSeconds());
    }

    private static Duration duration(String text) {
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("freshness_sla holds a value that is not an ISO-8601 duration: " + text, e);
        }
    }

    private static QuarantineEntry masked(QuarantineEntry e) {
        return new QuarantineEntry(
                e.id(),
                e.receptionId(),
                e.source(),
                e.sourceEventId(),
                e.reasonCode(),
                Redaction.text(e.detail()),
                e.receivedAt(),
                e.quarantinedAt(),
                e.replayOf(),
                e.replays(),
                e.latestReplayStatus(),
                e.subjectType(),
                e.subjectId());
    }

    static Source source(String code) {
        return Source.fromCode(code)
                .orElseThrow(() -> AdminException.invalid("UNKNOWN_SOURCE", "Unknown source " + code));
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
