package com.djavafactory.castor.core.port.application;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.port.domain.PortRepository;

/** Use case: the control points of a port as a GeoJSON FeatureCollection. */
@ApplicationScoped
public class GetControlPoints {

    private final PortRepository repository;

    public GetControlPoints(PortRepository repository) {
        this.repository = repository;
    }

    public String execute(String portCode) {
        return repository.controlPointsGeoJson(
                PortLookup.require(repository, portCode).id());
    }
}
