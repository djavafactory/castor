package it.replica8.castor.core.layout.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.layout.domain.LayoutRepository;
import it.replica8.castor.core.layout.domain.LayoutVersion;

/** Use case: the latest version of every layout. */
@ApplicationScoped
public class ListLayoutVersions {

    private final LayoutRepository repository;

    public ListLayoutVersions(LayoutRepository repository) {
        this.repository = repository;
    }

    public List<LayoutVersion> execute() {
        return repository.findLatestVersions();
    }
}
