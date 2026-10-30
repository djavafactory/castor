package com.djavafactory.castor.core.operations.application;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.CanonicalEventException;
import com.djavafactory.castor.contracts.CanonicalEventValidator;
import com.djavafactory.castor.contracts.Violation;
import com.djavafactory.castor.core.operations.domain.CanonicalEventRecord;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.EventLogRepository;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;

/**
 * One event, one database transaction: the event is checked, marked processed (a second delivery stops here), stored as
 * lineage, and handed to the handler of its type, which changes state and records the state change. The transaction
 * commits when {@link #apply} returns; a rejection or any other exception rolls all of it back.
 */
@ApplicationScoped
public class EventApplier {

    /** The consumer name of ops.processed_event and ops.dead_letter. */
    public static final String CONSUMER = "castor-core-projector";

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final PortRepository ports;
    private final EventLogRepository log;
    private final HandlerRegistry handlers;
    private final ProjectorFaults faults;
    private final ObjectMapper lineageJson;
    private final CanonicalEventCodec codec = new CanonicalEventCodec();
    private final CanonicalEventValidator validator = new CanonicalEventValidator();

    EventApplier(
            PortRepository ports,
            EventLogRepository log,
            HandlerRegistry handlers,
            ProjectorFaults faults,
            ObjectMapper json) {
        this.ports = ports;
        this.log = log;
        this.handlers = handlers;
        this.faults = faults;
        // lineage keeps a number as the source wrote it
        this.lineageJson = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** False when the event had been applied before: nothing changed. */
    @Transactional
    public boolean apply(IncomingRecord record) {
        String text = new String(record.value(), java.nio.charset.StandardCharsets.UTF_8);
        CanonicalEvent event = decode(text);
        UUID eventId = eventId(event);
        Port port = port(event);
        EventHandler handler = handlers.find(event.type())
                .orElseThrow(() -> new RejectedEventException(
                        DeadLetterReason.UNKNOWN_TYPE, "No handler for event type '" + event.type() + "'"));
        List<Violation> violations = validator.validate(text);
        if (!violations.isEmpty()) {
            throw new RejectedEventException(DeadLetterReason.INVALID_EVENT, describe(violations));
        }
        if (!log.markProcessed(port.id(), CONSUMER, eventId)) {
            return false;
        }
        log.append(new CanonicalEventRecord(
                eventId,
                port.id(),
                event.type(),
                event.aggregateId(),
                event.occurredAt().truncatedTo(ChronoUnit.MICROS),
                event.receivedAt().truncatedTo(ChronoUnit.MICROS),
                event.quality(),
                body(text)));
        faults.beforeHandle(event);
        handler.handle(new ProjectionContext(port.id(), eventId, event));
        return true;
    }

    private CanonicalEvent decode(String text) {
        try {
            return codec.decode(text);
        } catch (CanonicalEventException e) {
            throw new RejectedEventException(DeadLetterReason.INVALID_EVENT, e.getMessage());
        }
    }

    private static UUID eventId(CanonicalEvent event) {
        try {
            return UUID.fromString(event.eventId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RejectedEventException(
                    DeadLetterReason.INVALID_EVENT, "eventId '" + event.eventId() + "' is not a UUID");
        }
    }

    private Port port(CanonicalEvent event) {
        if (event.portId() == null || event.portId().isBlank()) {
            throw new RejectedEventException(DeadLetterReason.INVALID_EVENT, "portId is required");
        }
        return ports.findByCode(event.portId().toLowerCase())
                .orElseThrow(() -> new RejectedEventException(
                        DeadLetterReason.UNKNOWN_PORT, "No port with code '" + event.portId() + "'"));
    }

    private Map<String, Object> body(String text) {
        try {
            return lineageJson.readValue(text, OBJECT);
        } catch (IOException e) {
            throw new RejectedEventException(DeadLetterReason.INVALID_EVENT, "Event is not a JSON object");
        }
    }

    private static String describe(List<Violation> violations) {
        StringBuilder text = new StringBuilder();
        for (Violation v : violations) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append(v.path().isEmpty() ? "event" : v.path()).append(": ").append(v.message());
        }
        return text.toString();
    }
}
