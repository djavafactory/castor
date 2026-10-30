package com.djavafactory.castor.gateway.intake.domain;

import java.time.Instant;
import java.util.Optional;

/** The configured sources of every port and their API keys. */
public interface SourceRegistry {

    Optional<IntegrationSource> find(String portId, Source source);

    /** True when {@code keyHash} is the hash of a key of this source that has not been revoked. */
    boolean hasActiveKey(long sourceId, byte[] keyHash);

    void addKey(long sourceId, String prefix, byte[] keyHash, Instant createdAt);
}
