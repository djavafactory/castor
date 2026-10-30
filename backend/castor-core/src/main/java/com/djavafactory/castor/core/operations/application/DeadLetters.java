package com.djavafactory.castor.core.operations.application;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.CanonicalEventException;
import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.core.operations.domain.DeadLetter;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.DeadLetterRepository;
import com.djavafactory.castor.core.port.domain.PortRepository;

/**
 * Sets an event aside: a row in ops.dead_letter (its own transaction, so it survives the rollback of the failed one) and
 * a message on castor.integration.dlq.v1 with the cause and the source reference. If either cannot be written the
 * exception reaches the consumer, which stops without acknowledging: the event is delivered again after a restart,
 * never lost.
 */
@ApplicationScoped
public class DeadLetters {

    private static final int MAX_ERROR = 2000;
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final DeadLetterRepository letters;
    private final PortRepository ports;
    private final EventSender sender;
    private final ObjectMapper json;
    private final CanonicalEventCodec codec = new CanonicalEventCodec();

    DeadLetters(DeadLetterRepository letters, PortRepository ports, EventSender sender, ObjectMapper json) {
        this.letters = letters;
        this.ports = ports;
        this.sender = sender;
        this.json = json;
    }

    public DeadLetter record(IncomingRecord record, DeadLetterReason reason, String cause, int attempts) {
        return record(record, reason, cause, attempts, null);
    }

    /** {@code replayOf} is the dead letter this one is a new attempt of; the original is never changed. */
    public DeadLetter record(
            IncomingRecord record, DeadLetterReason reason, String cause, int attempts, Long replayOf) {
        String text = new String(record.value(), StandardCharsets.UTF_8);
        CanonicalEvent event = decode(text);
        Map<String, Object> body = body(text);
        String error = reason + ": " + (cause.length() > MAX_ERROR ? cause.substring(0, MAX_ERROR) : cause);

        DeadLetter stored = QuarkusTransaction.requiringNew()
                .call(() -> letters.add(new DeadLetter(
                        null,
                        event == null || event.portId() == null
                                ? null
                                : ports.findByCode(event.portId().toLowerCase())
                                        .map(p -> p.id())
                                        .orElse(null),
                        EventApplier.CONSUMER,
                        eventId(event),
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        error,
                        attempts,
                        body,
                        replayOf)));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("deadLetterId", stored.id());
        if (replayOf != null) {
            message.put("replayOf", replayOf);
        }
        message.put("consumer", EventApplier.CONSUMER);
        message.put("reason", reason.name());
        message.put("cause", cause);
        message.put("attempts", attempts);
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("topic", record.topic());
        from.put("partition", record.partition());
        from.put("offset", record.offset());
        message.put("source", from);
        message.put("reference", reference(event));
        message.put("event", body);
        String key = event != null && event.aggregateId() != null
                ? event.aggregateId()
                : record.key() != null ? record.key() : "unknown";
        try {
            sender.send(
                    Topics.INTEGRATION_DLQ,
                    key,
                    json.writeValueAsBytes(message),
                    Map.of("reason", reason.name(), "deadLetterId", String.valueOf(stored.id())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode the dead letter message", e);
        }
        return stored;
    }

    private CanonicalEvent decode(String text) {
        try {
            return codec.decode(text);
        } catch (CanonicalEventException e) {
            return null;
        }
    }

    private Map<String, Object> body(String text) {
        try {
            return json.readValue(text, OBJECT);
        } catch (JsonProcessingException | RuntimeException e) {
            return Map.of("raw", text);
        }
    }

    private static UUID eventId(CanonicalEvent event) {
        try {
            return event == null ? null : UUID.fromString(event.eventId());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Where the event came from, for whoever reads the dead letter without the original message. */
    private static Map<String, Object> reference(CanonicalEvent event) {
        Map<String, Object> reference = new LinkedHashMap<>();
        if (event != null) {
            reference.put("portId", event.portId());
            reference.put("sourceSystem", event.sourceSystem());
            reference.put("sourceEventId", event.sourceEventId());
            reference.put("receptionId", event.receptionId());
            reference.put("eventId", event.eventId());
            reference.put("type", event.type());
            reference.put("entityId", event.entityId());
        }
        return reference;
    }
}
