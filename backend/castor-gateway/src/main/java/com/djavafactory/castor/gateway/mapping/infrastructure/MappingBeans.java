package com.djavafactory.castor.gateway.mapping.infrastructure;

import java.time.InstantSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import com.djavafactory.castor.gateway.mapping.domain.MappingStore;
import com.djavafactory.castor.gateway.mapping.domain.Mappings;

/** {@link Mappings} is plain Java; this makes it injectable. One instance, so its cache is shared by every adapter. */
@ApplicationScoped
public class MappingBeans {

    @Produces
    @Singleton
    Mappings mappings(MappingStore store, InstantSource clock) {
        return new Mappings(store, clock);
    }
}
