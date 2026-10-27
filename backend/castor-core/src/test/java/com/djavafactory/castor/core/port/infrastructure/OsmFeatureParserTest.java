package com.djavafactory.castor.core.port.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.port.domain.DataSourceCode;
import com.djavafactory.castor.core.port.domain.ReferenceFeature;
import com.djavafactory.castor.core.port.domain.ReferenceKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OsmFeatureParserTest {

    private final OsmFeatureParser parser = new OsmFeatureParser(new ObjectMapper());

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String file(String featureProperties) {
        return "{\"type\":\"FeatureCollection\",\"castor\":{\"kind\":\"reference-features\",\"port\":\"westports\"},"
                + "\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[101.3,2.9]},"
                + "\"properties\":" + featureProperties + "}]}";
    }

    @Test
    void readsTheSampleFile() throws IOException {
        byte[] sample = Files.readAllBytes(Path.of("src/test/resources/fixtures/osm-sample.geojson"));

        List<ReferenceFeature> features = parser.parse(sample, "westports");

        assertEquals(4, features.size());
        ReferenceFeature shed = features.get(0);
        assertEquals(ReferenceKind.BUILDING, shed.kind());
        assertEquals("way/1001", shed.osmRef());
        assertEquals("Shed A", shed.name());
        assertEquals(12, shed.heightM().intValue());
        assertEquals(DataSourceCode.OSM, shed.source());
        assertNull(features.get(1).name());
        assertEquals(10, features.get(2).widthM().intValue());
    }

    @Test
    void refusesAFileForAnotherPort() {
        var e = assertThrows(IOException.class, () -> parser.parse(bytes(file("{}")), "other-port"));
        assertEquals("File is for port 'westports', expected 'other-port'", e.getMessage());
    }

    @Test
    void refusesAWrongHeaderKind() {
        String json =
                "{\"type\":\"FeatureCollection\",\"castor\":{\"kind\":\"layout\",\"port\":\"westports\"},\"features\":[]}";
        assertThrows(IOException.class, () -> parser.parse(bytes(json), "westports"));
    }

    @Test
    void refusesUnknownKindMissingRefAndUnknownSource() {
        assertThrows(
                IOException.class,
                () -> parser.parse(
                        bytes(file("{\"kind\":\"CASTLE\",\"osmRef\":\"way/1\",\"source\":\"OSM\"}")), "westports"));
        assertThrows(
                IOException.class,
                () -> parser.parse(bytes(file("{\"kind\":\"BUILDING\",\"source\":\"OSM\"}")), "westports"));
        assertThrows(
                IOException.class,
                () -> parser.parse(
                        bytes(file("{\"kind\":\"BUILDING\",\"osmRef\":\"way/1\",\"source\":\"NOPE\"}")), "westports"));
    }
}
