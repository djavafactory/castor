package com.djavafactory.castor.core.layout.domain;

import com.djavafactory.castor.core.geometry.domain.LocalPoint;

/**
 * Metric measurements of one asset, computed from the stored geometry in the port's measurement CRS; never stored.
 * Polygons have an area and perimeter, lines a length. Chainage (start and end along the quay) is set for berths.
 */
public record AssetMeasures(Double areaM2, Double perimeterM, Double lengthM, LocalPoint local, Chainage chainage) {

    public record Chainage(double startM, double endM) {}
}
