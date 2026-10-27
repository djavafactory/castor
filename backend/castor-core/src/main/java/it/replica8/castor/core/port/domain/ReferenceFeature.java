package it.replica8.castor.core.port.domain;

/**
 * A read-only feature taken from OpenStreetMap (a building outline or a road centreline).
 *
 * @param osmRef OpenStreetMap reference such as way/1300405460
 * @param name the mapped name, or null when OpenStreetMap has none
 * @param heightM height of a building in metres, null when unknown
 * @param areaM2 footprint area measured in the port's measurement CRS, null for lines
 * @param geometryGeoJson WGS84 geometry as a GeoJSON string
 */
public record ReferenceFeature(
        String osmRef,
        String kind,
        String name,
        Double heightM,
        Double areaM2,
        String source,
        String accuracy,
        String geometryGeoJson) {}
