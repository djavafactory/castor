package com.djavafactory.castor.core.operations.domain;

import java.util.List;
import java.util.Optional;

public interface DeadLetterRepository {

    DeadLetter add(DeadLetter letter);

    Optional<DeadLetter> find(long id);

    /** Newest first. */
    List<DeadLetter> findByConsumer(long portId, String consumer, int limit);
}
