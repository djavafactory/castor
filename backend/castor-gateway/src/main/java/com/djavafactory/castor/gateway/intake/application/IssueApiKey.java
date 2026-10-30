package com.djavafactory.castor.gateway.intake.application;

import java.time.InstantSource;
import java.util.random.RandomGenerator;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.intake.domain.ApiKeys;
import com.djavafactory.castor.gateway.intake.domain.IntegrationSource;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.intake.domain.SourceRegistry;

/**
 * Creates an API key for a source. The key is returned here and nowhere else: the database keeps its hash and its first
 * characters, so a lost key is replaced, never recovered.
 */
@ApplicationScoped
public class IssueApiKey {

    /**
     * @param key the whole key, to be handed to the source once
     * @param prefix the label stored next to the hash
     */
    public record IssuedKey(String key, String prefix) {}

    private final SourceRegistry sources;
    private final InstantSource clock;
    private final RandomGenerator random;

    public IssueApiKey(SourceRegistry sources, InstantSource clock, RandomGenerator random) {
        this.sources = sources;
        this.clock = clock;
        this.random = random;
    }

    public IssuedKey issue(String portId, Source source) {
        IntegrationSource found = sources.find(portId, source)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Port " + portId + " has no source " + source.code() + " configured"));
        String key = ApiKeys.generate(random);
        sources.addKey(found.id(), ApiKeys.prefix(key), ApiKeys.hash(key), clock.instant());
        return new IssuedKey(key, ApiKeys.prefix(key));
    }
}
