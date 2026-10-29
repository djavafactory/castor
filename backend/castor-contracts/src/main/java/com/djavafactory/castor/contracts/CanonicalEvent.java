package com.djavafactory.castor.contracts;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The canonical event envelope, schema version 1. Every ID is a string. {@code sourceSystem}, {@code quality} and
 * {@code occurredAt} are the source, quality and observedAt that rules R2 and R3 attach to every value.
 *
 * @param eventId UUID, set by the gateway
 * @param sourceSequence optional ordering number given by the source
 * @param aggregateId the Kafka key
 * @param correlationId optional, links events that belong together
 * @param payload validated against the schema of {@code type}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalEvent(
        String eventId,
        int schemaVersion,
        String portId,
        String sourceSystem,
        String sourceEventId,
        Long sourceSequence,
        String receptionId,
        String type,
        String entityType,
        String entityId,
        String aggregateId,
        Instant occurredAt,
        Instant receivedAt,
        String correlationId,
        Quality quality,
        JsonNode payload) {

    public static final int SCHEMA_VERSION = 1;
}
