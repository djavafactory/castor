package com.djavafactory.castor.core.operations.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A message the projector gave up on. {@code reason} is the first word of {@code error}. {@code state} is OPEN (never
 * replayed), REPLAYED (the latest replay was applied) or REPLAY_FAILED. A replay is a row of its own whose
 * {@code replayOf} names the original, which is never changed. {@code event} is the message as received, {@code type}
 * and {@code entityId} are read from it.
 */
public record DeadLetterDto(
        long id,
        UUID eventId,
        String consumer,
        String topic,
        int partition,
        long offset,
        String reason,
        String error,
        int attempts,
        Instant createdAt,
        Long replayOf,
        String state,
        int replays,
        Long lastReplayId,
        String type,
        String sourceSystem,
        String entityId,
        Map<String, Object> event,
        List<DeadLetterDto> replayAttempts) {}
