package com.djavafactory.castor.core.operations.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.operations.api.DeadLetterDto;
import com.djavafactory.castor.core.operations.api.DeadLetterReplayDto;
import com.djavafactory.castor.core.operations.domain.DeadLetter;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.DeadLetterRepository;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * Reads the dead letters of a port and replays them. A replay applies the stored event again through the same
 * {@link EventApplier} as the consumer (one transaction; the processed-event guard keeps it exactly-once), without
 * waiting for Kafka. It is a new attempt: a row of ops.dead_letter whose {@code replay_of} names the original, which is
 * never changed or deleted. A replay that works leaves a row whose error starts with {@value #REPLAYED}; one that fails
 * again leaves a dead letter like any other, linked the same way. Every replay is audited, whatever its outcome.
 */
@ApplicationScoped
public class DeadLetterReplays {

    /** The first word of the error of an attempt that was applied. */
    public static final String REPLAYED = "REPLAYED";

    static final String AUDIT_KIND = "DEAD_LETTER";
    static final int LIMIT = 200;

    private final DeadLetterRepository letters;
    private final EventApplier applier;
    private final DeadLetters deadLetters;
    private final AuditService audit;
    private final ObjectMapper json;

    DeadLetterReplays(
            DeadLetterRepository letters,
            EventApplier applier,
            DeadLetters deadLetters,
            AuditService audit,
            ObjectMapper json) {
        this.letters = letters;
        this.applier = applier;
        this.deadLetters = deadLetters;
        this.audit = audit;
        this.json = json;
    }

    /** First attempts, newest first, each with the state of its replays. */
    @Transactional
    public List<DeadLetterDto> list(long portId) {
        return letters.listOriginals(portId, LIMIT).stream()
                .map(e -> entry(e, List.of()))
                .toList();
    }

    @Transactional
    public DeadLetterDto get(long portId, long id) {
        var entry =
                letters.original(portId, id).orElseThrow(() -> new ResourceNotFoundException("No dead letter " + id));
        List<DeadLetterDto> attempts =
                letters.replaysOf(id).stream().map(r -> dto(r, null)).toList();
        return entry(entry, attempts);
    }

    /** Applies the stored event again. Not transactional itself: the apply and the attempt row commit on their own. */
    public DeadLetterReplayDto replay(String portCode, long portId, long id) {
        DeadLetter original = QuarkusTransaction.requiringNew()
                .call(() -> letters.find(id)
                        .filter(l -> l.replayOf() == null && Objects.equals(l.portId(), portId))
                        .orElseThrow(() -> new ResourceNotFoundException("No dead letter " + id)));
        Map<String, Object> body = original.body();
        if (body == null || (body.containsKey("raw") && body.size() == 1)) {
            throw new InvalidRequestException("Dead letter " + id + " holds no readable event to replay");
        }
        IncomingRecord record =
                new IncomingRecord(original.topic(), original.partition(), original.offset(), key(body), bytes(body));
        String outcome;
        DeadLetter attempt;
        try {
            boolean applied = applier.apply(record);
            outcome = applied ? "APPLIED" : "ALREADY_APPLIED";
            String note = REPLAYED + ": " + (applied ? "applied" : "had been applied already");
            attempt = QuarkusTransaction.requiringNew()
                    .call(() -> letters.add(new DeadLetter(
                            null,
                            original.portId(),
                            original.consumer(),
                            original.eventId(),
                            original.topic(),
                            original.partition(),
                            original.offset(),
                            note,
                            1,
                            body,
                            original.id())));
        } catch (RejectedEventException e) {
            outcome = "FAILED";
            attempt = deadLetters.record(record, e.reason(), e.getMessage(), 1, original.id());
        } catch (RuntimeException e) {
            outcome = "FAILED";
            attempt = deadLetters.record(
                    record,
                    DeadLetterReason.PROCESSING_FAILED,
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    1,
                    original.id());
        }
        recordAudit(portCode, original, attempt, outcome);
        return new DeadLetterReplayDto(outcome, dto(attempt, null));
    }

    private void recordAudit(String portCode, DeadLetter original, DeadLetter attempt, String outcome) {
        ObjectNode after = JsonNodeFactory.instance
                .objectNode()
                .put("outcome", outcome)
                .put("attemptId", attempt.id())
                .put("error", attempt.error());
        QuarkusTransaction.requiringNew()
                .run(() -> audit.recordForPort(
                        portCode,
                        AuditAction.DEAD_LETTER_REPLAYED,
                        AUDIT_KIND,
                        String.valueOf(original.id()),
                        null,
                        after));
    }

    private static String key(Map<String, Object> body) {
        Object aggregate = body.get("aggregateId");
        return aggregate == null ? null : aggregate.toString();
    }

    private byte[] bytes(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("The stored event cannot be read back");
        }
    }

    private static DeadLetterDto entry(DeadLetterRepository.Entry e, List<DeadLetterDto> attempts) {
        String state = e.replays() == 0
                ? "OPEN"
                : e.lastReplayError() != null && e.lastReplayError().startsWith(REPLAYED)
                        ? "REPLAYED"
                        : "REPLAY_FAILED";
        DeadLetterDto d = dto(e.letter(), e.createdAt());
        return new DeadLetterDto(
                d.id(),
                d.eventId(),
                d.consumer(),
                d.topic(),
                d.partition(),
                d.offset(),
                d.reason(),
                d.error(),
                d.attempts(),
                d.createdAt(),
                d.replayOf(),
                state,
                e.replays(),
                e.lastReplayId(),
                d.type(),
                d.sourceSystem(),
                d.entityId(),
                d.event(),
                attempts);
    }

    private static DeadLetterDto dto(DeadLetter l, Instant createdAt) {
        String error = l.error();
        int colon = error.indexOf(':');
        Map<String, Object> body = l.body();
        return new DeadLetterDto(
                l.id(),
                l.eventId(),
                l.consumer(),
                l.topic(),
                l.partition(),
                l.offset(),
                colon < 0 ? error : error.substring(0, colon),
                error,
                l.attempts(),
                createdAt,
                l.replayOf(),
                l.replayOf() == null ? "OPEN" : error.startsWith(REPLAYED) ? "REPLAYED" : "REPLAY_FAILED",
                0,
                null,
                text(body, "type"),
                text(body, "sourceSystem"),
                text(body, "entityId"),
                body,
                null);
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value == null ? null : value.toString();
    }
}
