package com.djavafactory.castor.gateway.admin.domain;

import java.time.Instant;
import java.util.List;

import com.djavafactory.castor.gateway.intake.domain.Source;

/** The API keys of one source, as the operators manage them. Only hashes are stored: a key is never readable again. */
public interface ApiKeyStore {

    /**
     * @param origin {@code issued} for a key made here, {@code env} for the demo key of GATEWAY_DEMO_KEY
     * @param revokedAt null while the key works
     */
    record ApiKeySummary(long id, String prefix, String origin, Instant createdAt, Instant revokedAt) {}

    /** Every key of the source, newest first.
     *
     * @throws AdminException the port has no such source
     */
    List<ApiKeySummary> keys(String portId, Source source);

    /** Adds a key (only its hash and label are stored) and returns it.
     *
     * @throws AdminException the port has no such source
     */
    ApiKeySummary add(String portId, Source source, String prefix, byte[] hash, Instant now);

    /**
     * In one transaction revokes the key and adds the new one.
     *
     * @throws AdminException the key is not there, is revoked already or is the demo key
     */
    ApiKeySummary rotate(String portId, Source source, long keyId, String prefix, byte[] hash, Instant now);

    /**
     * @throws AdminException the key is not there, is revoked already or is the demo key
     */
    ApiKeySummary revoke(String portId, Source source, long keyId, Instant now);
}
