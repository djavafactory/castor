package com.djavafactory.castor.core.geometry.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoJsonMapperTest {

    private final GeoJsonMapper mapper = new GeoJsonMapper(new ObjectMapper());
    private final GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void knownPolygonGivesTheExactGeoJsonString() {
        var polygon = factory.createPolygon(new Coordinate[] {
            new Coordinate(101.3066304, 2.9534057),
            new Coordinate(101.3067304, 2.9534057),
            new Coordinate(101.3067304, 2.953505),
            new Coordinate(101.3066304, 2.9534057)
        });

        assertEquals(
                "{\"type\":\"Polygon\",\"coordinates\":[[[101.3066304,2.9534057],[101.3067304,2.9534057],"
                        + "[101.3067304,2.953505],[101.3066304,2.9534057]]]}",
                mapper.write(polygon));
    }

    @Test
    void longitudeComesFirstAndNoCrsMemberIsWritten() {
        assertEquals(
                "{\"type\":\"Point\",\"coordinates\":[101.3,2.95]}",
                mapper.write(factory.createPoint(new Coordinate(101.3, 2.95))));
    }

    @Test
    void writesAtMostSevenDecimals() {
        var p = factory.createPoint(new Coordinate(101.123456789, 2.987654321));
        assertEquals("{\"type\":\"Point\",\"coordinates\":[101.1234568,2.9876543]}", mapper.write(p));
    }

    @Test
    void readSetsTheSrid() {
        var g = mapper.read("{\"type\":\"Point\",\"coordinates\":[101.3,2.95]}");
        assertEquals(4326, g.getSRID());
    }

    @Test
    void featureCarriesIdGeometryAndProperties() {
        var props = new ObjectMapper().createObjectNode().put("code", "B1");
        var f = mapper.feature("abc", factory.createPoint(new Coordinate(1, 2)), props);
        assertEquals("Feature", f.get("type").asText());
        assertEquals("abc", f.get("id").asText());
        assertEquals("Point", f.get("geometry").get("type").asText());
        assertEquals("B1", f.get("properties").get("code").asText());
    }
}
