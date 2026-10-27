package com.djavafactory.castor.core.port.infrastructure;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.djavafactory.castor.core.port.domain.AccuracyClass;
import com.djavafactory.castor.core.port.domain.DataSourceCode;
import com.djavafactory.castor.core.port.domain.ReferenceFeature;
import com.djavafactory.castor.core.port.domain.ReferenceKind;

/**
 * Reads the reference GeoJSON (header member "castor" plus one Feature per shape) and refuses anything that does not
 * match the documented format, so a bad file never half-loads.
 */
final class OsmFeatureParser {

    static final String HEADER_KIND = "reference-features";
    private static final String GENERIC_NAME_PREFIX = "Building ";

    private final ObjectMapper mapper;

    OsmFeatureParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @param expectedPort the code in the file header must match */
    List<ReferenceFeature> parse(byte[] json, String expectedPort) throws IOException {
        JsonNode root = mapper.readTree(json);
        JsonNode header = root.path("castor");
        if (!"FeatureCollection".equals(root.path("type").asText())) {
            throw new IOException("Not a GeoJSON FeatureCollection");
        }
        if (!HEADER_KIND.equals(header.path("kind").asText())) {
            throw new IOException("Header castor.kind must be " + HEADER_KIND);
        }
        if (!expectedPort.equals(header.path("port").asText())) {
            throw new IOException(
                    "File is for port '" + header.path("port").asText() + "', expected '" + expectedPort + "'");
        }
        List<ReferenceFeature> features = new ArrayList<>();
        for (JsonNode feature : root.path("features")) {
            features.add(feature(feature, features.size()));
        }
        return features;
    }

    private ReferenceFeature feature(JsonNode feature, int index) throws IOException {
        JsonNode props = feature.path("properties");
        JsonNode geometry = feature.path("geometry");
        String at = "feature " + index;
        if (geometry.isMissingNode() || geometry.isNull()) {
            throw new IOException(at + " has no geometry");
        }
        String osmRef = text(props, "osmRef");
        if (osmRef == null) {
            throw new IOException(at + " has no osmRef");
        }
        return new ReferenceFeature(
                kind(props, at),
                osmRef,
                name(props),
                number(props, "heightM"),
                number(props, "widthM"),
                mapper.writeValueAsString(geometry),
                mapper.writeValueAsString(
                        props.path("tags").isObject() ? props.get("tags") : mapper.createObjectNode()),
                source(props, at),
                accuracy(props, at));
    }

    private static ReferenceKind kind(JsonNode props, String at) throws IOException {
        try {
            return ReferenceKind.valueOf(props.path("kind").asText(""));
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    at + " has unknown kind '" + props.path("kind").asText("") + "'");
        }
    }

    private static DataSourceCode source(JsonNode props, String at) throws IOException {
        try {
            return DataSourceCode.valueOf(props.path("source").asText(""));
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    at + " has unknown source '" + props.path("source").asText("") + "'");
        }
    }

    private static AccuracyClass accuracy(JsonNode props, String at) throws IOException {
        if (!props.hasNonNull("accuracy")) {
            return AccuracyClass.APPROXIMATE;
        }
        try {
            return AccuracyClass.valueOf(props.get("accuracy").asText());
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    at + " has unknown accuracy '" + props.get("accuracy").asText() + "'");
        }
    }

    /** The extract names unnamed buildings "Building <id>"; that is not a mapped name, so it is not stored. */
    private static String name(JsonNode props) {
        String name = text(props, "name");
        return name == null || name.startsWith(GENERIC_NAME_PREFIX) ? null : name;
    }

    private static String text(JsonNode props, String field) {
        JsonNode value = props.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static BigDecimal number(JsonNode props, String field) {
        JsonNode value = props.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }
}
