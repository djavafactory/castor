package com.djavafactory.castor.core.operations.domain;

import java.util.Map;
import java.util.UUID;

/**
 * A message a consumer gave up on, with its place in Kafka. replayOf names the entry it re-sends. portId is null when
 * the message never named a port Castor knows.
 */
public record DeadLetter(
        Long id,
        Long portId,
        String consumer,
        UUID eventId,
        String topic,
        int partition,
        long offset,
        String error,
        int attempts,
        Map<String, Object> body,
        Long replayOf) {}
