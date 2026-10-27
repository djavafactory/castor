package it.replica8.castor.core.port.domain;

import java.math.BigDecimal;

/**
 * A read-only background shape (building, road, coastline ...), for example from OpenStreetMap. Not a layout asset.
 *
 * @param geometryJson GeoJSON geometry text, WGS84
 * @param tagsJson     source tags as a JSON object
 */
public record ReferenceFeature(
        ReferenceKind kind,
        String osmRef,
        String name,
        BigDecimal heightM,
        BigDecimal widthM,
        String geometryJson,
        String tagsJson,
        DataSourceCode source) {}
