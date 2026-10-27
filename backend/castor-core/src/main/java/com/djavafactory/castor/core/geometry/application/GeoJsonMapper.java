package com.djavafactory.castor.core.geometry.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;

/**
 * The one place that turns geometry into GeoJSON text and back: JTS {@code GeoJsonWriter}, [lon, lat], 7 decimals.
 * Every endpoint and export goes through it; no code writes coordinates by hand.
 */
@ApplicationScoped
public class GeoJsonMapper {

    public static final int DECIMALS = 7;

    private final ObjectMapper json;

    public GeoJsonMapper(ObjectMapper json) {
        this.json = json;
    }

    /** GeoJSON geometry text, for example a Point as {"type":"Point","coordinates":[101.3,2.95]}. */
    public String write(Geometry geometry) {
        var writer = new GeoJsonWriter(DECIMALS);
        writer.setEncodeCRS(false);
        return writer.write(geometry);
    }

    /** The same geometry as a JSON tree, to put into a feature. */
    public JsonNode node(Geometry geometry) {
        try {
            return json.readTree(write(geometry));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the GeoJSON just written", e);
        }
    }

    /** A Feature with the given id, geometry and properties. */
    public ObjectNode feature(String id, Geometry geometry, ObjectNode properties) {
        ObjectNode feature = json.createObjectNode();
        feature.put("type", "Feature");
        feature.put("id", id);
        feature.set("geometry", node(geometry));
        feature.set("properties", properties);
        return feature;
    }

    public ObjectNode featureCollection(List<ObjectNode> features) {
        ObjectNode collection = json.createObjectNode();
        collection.put("type", "FeatureCollection");
        collection.set("features", json.createArrayNode().addAll(features));
        return collection;
    }

    /** Reads a GeoJSON geometry; SRID 4326 is set on the result. */
    public Geometry read(String geoJson) {
        try {
            Geometry g = new GeoJsonReader().read(geoJson);
            g.setSRID(4326);
            return g;
        } catch (ParseException e) {
            throw new IllegalArgumentException("Not a GeoJSON geometry: " + e.getMessage(), e);
        }
    }
}
