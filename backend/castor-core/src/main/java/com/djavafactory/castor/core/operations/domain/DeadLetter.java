package com.djavafactory.castor.core.operations.domain;

import java.util.Map;
import java.util.UUID;

/** A message a consumer gave up on, with its place in Kafka. replayOf names the entry it re-sends. */
public record DeadLetter(
        Long id,
        long portId,
        String consumer,
        UUID eventId,
        String topic,
        int partition,
        long offset,
        String error,
        int attempts,
        Map<String, Object> body,
        Long replayOf) {}
