package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.Map;

/** One entry of the per-port change log; sequences start at 1 and have no gaps. publishedAt is null until sent. */
public record StateChange(
        long portId,
        long sequence,
        String entityType,
        String entityId,
        long entityVersion,
        Instant occurredAt,
        Map<String, Object> body,
        Instant publishedAt) {}
