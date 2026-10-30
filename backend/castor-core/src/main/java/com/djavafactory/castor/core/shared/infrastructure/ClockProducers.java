package com.djavafactory.castor.core.shared.infrastructure;

import java.time.InstantSource;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.quarkus.arc.DefaultBean;

import com.djavafactory.castor.core.shared.domain.ClockInfo;

/**
 * The server clock: every timestamp the service makes itself comes from the injected {@link InstantSource}, never from
 * Instant.now(). Both beans are defaults, so the demo clock of EMULATOR-02 replaces them by declaring its own.
 */
public class ClockProducers {

    @Produces
    @Singleton
    @DefaultBean
    InstantSource instantSource() {
        return InstantSource.system();
    }

    @Produces
    @Singleton
    @DefaultBean
    ClockInfo clockInfo() {
        return ClockInfo.SYSTEM;
    }
}
