package com.djavafactory.castor.gateway.shared.infrastructure;

import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.random.RandomGenerator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.quarkus.arc.DefaultBean;

import com.djavafactory.castor.gateway.shared.domain.Uuid7;

/**
 * The only places where the gateway touches the wall clock and the random source. Server code asks for an injected
 * {@link InstantSource} (rule R8); tests replace it with a stepped clock.
 */
@ApplicationScoped
public class Clocks {

    @Produces
    @DefaultBean
    @ApplicationScoped
    InstantSource instantSource() {
        return InstantSource.system();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    RandomGenerator randomGenerator() {
        return new SecureRandom();
    }

    /** One generator per process, so reception ids increase for every request, not only within one. */
    @Produces
    @Singleton
    Uuid7.Generator receptionIds(RandomGenerator random) {
        return new Uuid7.Generator(random);
    }
}
