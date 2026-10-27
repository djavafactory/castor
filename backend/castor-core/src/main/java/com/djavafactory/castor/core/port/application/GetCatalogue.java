package com.djavafactory.castor.core.port.application;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.port.domain.Catalogue;
import com.djavafactory.castor.core.port.domain.CatalogueRepository;

/** Use case: the cargo, ship and lookup catalogue. */
@ApplicationScoped
public class GetCatalogue {

    private final CatalogueRepository repository;

    public GetCatalogue(CatalogueRepository repository) {
        this.repository = repository;
    }

    public Catalogue execute() {
        return repository.load();
    }
}
