package it.replica8.castor.core.port.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.port.domain.PortRepository;
import it.replica8.castor.core.port.domain.PortSummary;

/** Use case: every port by code and name. */
@ApplicationScoped
public class ListPorts {

    private final PortRepository repository;

    public ListPorts(PortRepository repository) {
        this.repository = repository;
    }

    public List<PortSummary> execute() {
        return repository.findAll();
    }
}
