package com.djavafactory.castor.gateway.relay.domain;

import java.util.List;
import java.util.function.Function;

/** The outbox: canonical events written by the normalizer, waiting to be published. */
public interface OutboxStore {

    /**
     * In one transaction, and only while no other relay is running (in this process or another): reads up to
     * {@code batchSize} unpublished rows in publication order, hands them to {@code publish}, and sets
     * {@code published_at} on the ids it returns. If {@code publish} throws, nothing is marked.
     *
     * @return how many rows were marked published
     */
    int relay(int batchSize, Function<List<OutboxMessage>, List<Long>> publish);
}
