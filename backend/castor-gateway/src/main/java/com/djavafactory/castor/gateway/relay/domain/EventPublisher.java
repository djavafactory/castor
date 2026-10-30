package com.djavafactory.castor.gateway.relay.domain;

import java.util.List;

/** Where canonical events are published: Kafka topic castor.ingest.canonical.v1, keyed by aggregateId. */
public interface EventPublisher {

    /**
     * Sends the messages in the given order and waits for the broker's acknowledgement of each.
     *
     * @return one flag per message, in order: true when the broker acknowledged it. A broker that is away is not an
     *     exception, it is a list of false.
     */
    List<Boolean> publish(List<OutboxMessage> messages);
}
