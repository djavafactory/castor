package com.djavafactory.castor.core.operations.application;

import com.djavafactory.castor.contracts.EventType;

/**
 * Applies one type of canonical event to the operational state. One bean per type; the {@link HandlerRegistry} finds
 * it. A handler runs inside the transaction of the event: it throws {@code RejectedEventException} for an event that
 * can never be applied, and any other exception to ask for a retry.
 */
public interface EventHandler {

    EventType type();

    void handle(ProjectionContext context);
}
