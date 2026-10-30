package com.djavafactory.castor.gateway.normalize.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.CanonicalEventValidator;
import com.djavafactory.castor.contracts.Violation;
import com.djavafactory.castor.gateway.normalize.domain.EventIds;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.normalize.domain.SourceAdapter;
import com.djavafactory.castor.gateway.normalize.domain.ViolationText;
import com.djavafactory.castor.gateway.shared.domain.Json;

/**
 * The adapter of source {@code canonical}: the body already is a canonical envelope. The fields the gateway owns
 * ({@code eventId}, {@code receptionId}, {@code receivedAt} and {@code portId}, which is the port of the URL) are set
 * here whatever the sender wrote, then the whole event is checked with {@link CanonicalEventValidator}. A sender that
 * names another port is refused, because an event must not move to a port its key does not belong to.
 */
@ApplicationScoped
public class CanonicalPassThroughAdapter implements SourceAdapter {

    private final CanonicalEventValidator validator;
    private final CanonicalEventCodec codec;

    public CanonicalPassThroughAdapter(CanonicalEventValidator validator, CanonicalEventCodec codec) {
        this.validator = validator;
        this.codec = codec;
    }

    @Override
    public String source() {
        return "canonical";
    }

    @Override
    public List<CanonicalEvent> normalize(InboxRecord record) throws Rejection {
        if (record.raw() == null || !record.raw().isObject()) {
            throw new Rejection(Rejection.SCHEMA_INVALID, "An event must be a JSON object");
        }
        ObjectNode envelope = ((ObjectNode) record.raw()).deepCopy();
        JsonNode port = envelope.get("portId");
        if (port != null && !port.isNull() && !record.portId().equals(port.asText())) {
            throw new Rejection(
                    Rejection.PORT_MISMATCH,
                    "The event is for port " + port.asText() + " but was sent to port " + record.portId());
        }
        envelope.put("portId", record.portId());
        envelope.put("receptionId", record.receptionId().toString());
        envelope.put("receivedAt", record.receivedAt().toString());
        envelope.put("eventId", EventIds.of(record.receptionId(), 0).toString());

        List<Violation> violations = validator.validate(envelope);
        if (!violations.isEmpty()) {
            throw new Rejection(Rejection.SCHEMA_INVALID, ViolationText.describe(violations));
        }
        return List.of(codec.decode(Json.write(envelope)));
    }
}
