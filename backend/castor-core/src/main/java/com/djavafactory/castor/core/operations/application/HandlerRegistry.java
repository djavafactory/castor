package com.djavafactory.castor.core.operations.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/** The handlers by event type code. A type without a handler is unknown to the projector until one is added. */
@ApplicationScoped
public class HandlerRegistry {

    private final Map<String, EventHandler> byType = new HashMap<>();

    @Inject
    HandlerRegistry(@Any Instance<EventHandler> handlers) {
        this(handlers.stream().toList());
    }

    HandlerRegistry(List<EventHandler> handlers) {
        for (EventHandler handler : handlers) {
            if (byType.put(handler.type().code(), handler) != null) {
                throw new IllegalStateException(
                        "Two handlers for " + handler.type().code());
            }
        }
    }

    public Optional<EventHandler> find(String typeCode) {
        return Optional.ofNullable(byType.get(typeCode));
    }
}
