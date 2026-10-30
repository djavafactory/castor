package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeadLetterRepository {

    DeadLetter add(DeadLetter letter);

    Optional<DeadLetter> find(long id);

    /** Newest first. */
    List<DeadLetter> findByConsumer(long portId, String consumer, int limit);

    /**
     * A first attempt as the Data quality panel lists it: the entry, when it was set aside, and what became of the
     * replays of it. {@code lastReplayError} starts with REPLAYED when the latest replay was applied.
     */
    record Entry(DeadLetter letter, Instant createdAt, int replays, Long lastReplayId, String lastReplayError) {}

    /** First attempts of a port (not replays), newest first. */
    List<Entry> listOriginals(long portId, int limit);

    /** One first attempt of the port with the state of its replays. */
    Optional<Entry> original(long portId, long id);

    /** The replays of an entry, oldest first. */
    List<DeadLetter> replaysOf(long id);
}
