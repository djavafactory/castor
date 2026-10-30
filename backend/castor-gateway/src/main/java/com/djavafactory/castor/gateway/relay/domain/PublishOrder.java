package com.djavafactory.castor.gateway.relay.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which rows may be marked published. Events of one aggregate must reach Kafka in outbox order, so after an event the
 * broker did not acknowledge, no later event of the same aggregate is marked, even if the broker took it: the next run
 * sends them again, in order. Other aggregates are not held back.
 */
public final class PublishOrder {

    private PublishOrder() {}

    /** The ids to mark published, given the acknowledgement flag of each message (same order as {@code messages}). */
    public static List<Long> markable(List<OutboxMessage> messages, List<Boolean> acknowledged) {
        if (messages.size() != acknowledged.size()) {
            throw new IllegalArgumentException(
                    messages.size() + " messages but " + acknowledged.size() + " acknowledgements");
        }
        Set<String> blocked = new HashSet<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            OutboxMessage message = messages.get(i);
            if (blocked.contains(message.aggregateId())) {
                continue;
            }
            if (acknowledged.get(i)) {
                ids.add(message.id());
            } else {
                blocked.add(message.aggregateId());
            }
        }
        return ids;
    }
}
