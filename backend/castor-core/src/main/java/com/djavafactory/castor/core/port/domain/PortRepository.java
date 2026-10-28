package com.djavafactory.castor.core.port.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** What the port use cases need from storage. Implemented in infrastructure. */
public interface PortRepository {

    List<PortSummary> findAll();

    Optional<Port> findByCode(String code);

    List<DataSourceInfo> findDataSources();

    /** Berths in the published layout of the port; the count is derived, never stored. */
    int countPublishedBerths(Port port);

    /** GeoJSON FeatureCollection of the control points. */
    String controlPointsGeoJson(long portId);

    /** The control points of a port, typed, ordered by code; for the accuracy report (DESIGNER-11). */
    List<ControlPoint> controlPoints(long portId);

    /** GeoJSON FeatureCollection of the background features; a null box means the whole port. */
    String referenceFeaturesGeoJson(long portId, Set<ReferenceKind> kinds, BoundingBox box);
}
