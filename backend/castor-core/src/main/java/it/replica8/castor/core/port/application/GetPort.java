package it.replica8.castor.core.port.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.port.domain.DataSourceInfo;
import it.replica8.castor.core.port.domain.Port;
import it.replica8.castor.core.port.domain.PortRepository;

/** Use case: one port with its frame, derived berth count and the data sources. */
@ApplicationScoped
public class GetPort {

    /** Shown wherever map data is shown (SETUP-03). */
    public static final String ATTRIBUTION =
            "Imagery: Sentinel-2 cloudless by EOX IT Services GmbH (CC BY-NC-SA 4.0), modified Copernicus Sentinel"
                    + " data 2024. Outlines: (c) OpenStreetMap contributors (ODbL).";

    public record PortDetail(Port port, int berthCount, String attribution, List<DataSourceInfo> dataSources) {}

    private final PortRepository repository;

    public GetPort(PortRepository repository) {
        this.repository = repository;
    }

    public PortDetail execute(String code) {
        Port port = PortLookup.require(repository, code);
        return new PortDetail(port, repository.countPublishedBerths(port), ATTRIBUTION, repository.findDataSources());
    }
}
