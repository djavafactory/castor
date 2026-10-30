package com.djavafactory.castor.gateway.relay.domain;

/**
 * An outbox row ready to be sent.
 *
 * @param id the publication order
 * @param aggregateId the Kafka key
 * @param payload the canonical event as JSON bytes
 */
public record OutboxMessage(long id, String eventId, String aggregateId, String type, byte[] payload) {}
