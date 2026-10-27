package com.djavafactory.castor.archfixture.violation.domain;

import jakarta.validation.constraints.NotNull;

import com.djavafactory.castor.archfixture.violation.infrastructure.BadAdapter;

/** Deliberate violation: a domain type with a Jakarta import that also reaches into infrastructure. */
public record BadDomainRecord(@NotNull String name) {

    public String adapterName() {
        return new BadAdapter().name();
    }
}
