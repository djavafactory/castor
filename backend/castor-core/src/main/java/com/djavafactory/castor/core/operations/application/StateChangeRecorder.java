package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;

/**
 * The one way state is announced: appends to ops.state_change with the next sequence of the port, inside the
 * transaction that changed the state. A call outside a transaction fails, so a change can never be announced without
 * being stored, nor stored without being announced.
 */
@ApplicationScoped
public class StateChangeRecorder {

    private final StateChangeRepository changes;

    StateChangeRecorder(StateChangeRepository changes) {
        this.changes = changes;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public StateChange record(
            long portId,
            String entityType,
            String entityId,
            long entityVersion,
            Instant occurredAt,
            Map<String, Object> body) {
        if (entityType == null || entityType.isBlank() || entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityType and entityId are required");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(body, "body");
        return changes.append(portId, entityType, entityId, entityVersion, occurredAt, body);
    }
}
