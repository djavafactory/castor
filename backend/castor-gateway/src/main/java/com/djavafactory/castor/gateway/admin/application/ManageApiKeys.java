package com.djavafactory.castor.gateway.admin.application;

import java.time.InstantSource;
import java.util.List;
import java.util.random.RandomGenerator;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.admin.domain.ApiKeyStore;
import com.djavafactory.castor.gateway.admin.domain.ApiKeyStore.ApiKeySummary;
import com.djavafactory.castor.gateway.admin.domain.PortId;
import com.djavafactory.castor.gateway.intake.domain.ApiKeys;

/**
 * Create, rotate and revoke the API keys of a source. A new key is returned once, in the answer that creates it; the
 * database keeps its hash and its first characters only.
 */
@ApplicationScoped
public class ManageApiKeys {

    /**
     * @param key the whole key, to be handed to the source now: it cannot be read again
     */
    public record IssuedKey(ApiKeySummary summary, String key) {}

    private final ApiKeyStore store;
    private final InstantSource clock;
    private final RandomGenerator random;

    public ManageApiKeys(ApiKeyStore store, InstantSource clock, RandomGenerator random) {
        this.store = store;
        this.clock = clock;
        this.random = random;
    }

    public List<ApiKeySummary> list(String portId, String source) {
        return store.keys(PortId.require(portId), ReadEvidence.source(source));
    }

    public IssuedKey create(String portId, String source) {
        String key = ApiKeys.generate(random);
        ApiKeySummary summary = store.add(
                PortId.require(portId),
                ReadEvidence.source(source),
                ApiKeys.prefix(key),
                ApiKeys.hash(key),
                clock.instant());
        return new IssuedKey(summary, key);
    }

    /** Revokes the key and issues a new one in one step: the old key stops working at once. */
    public IssuedKey rotate(String portId, String source, long keyId) {
        String key = ApiKeys.generate(random);
        ApiKeySummary summary = store.rotate(
                PortId.require(portId),
                ReadEvidence.source(source),
                keyId,
                ApiKeys.prefix(key),
                ApiKeys.hash(key),
                clock.instant());
        return new IssuedKey(summary, key);
    }

    public ApiKeySummary revoke(String portId, String source, long keyId) {
        return store.revoke(PortId.require(portId), ReadEvidence.source(source), keyId, clock.instant());
    }
}
