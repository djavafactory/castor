package com.djavafactory.castor.core.search.domain;

import java.util.List;

/**
 * One search result: a place the map can jump to.
 *
 * @param id       port code, control point code, asset id or OpenStreetMap reference, by kind
 * @param sublabel the type of the thing, for example "Berth"
 * @param lon      WGS84 longitude of the place (the anchor of an asset)
 * @param lat      WGS84 latitude of the place
 * @param bbox     minLon, minLat, maxLon, maxLat of its shape; a single point has a box of no size
 */
public record PlaceHit(
        PlaceKind kind, String id, String label, String sublabel, double lon, double lat, List<Double> bbox) {}
