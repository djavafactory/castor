package com.djavafactory.castor.gateway.shared.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.CanonicalEventValidator;

/** The contract classes are plain Java; this makes them injectable. Both are safe to share between threads. */
@ApplicationScoped
public class ContractBeans {

    @Produces
    @ApplicationScoped
    CanonicalEventValidator validator() {
        return new CanonicalEventValidator();
    }

    @Produces
    @ApplicationScoped
    CanonicalEventCodec codec() {
        return new CanonicalEventCodec();
    }
}
