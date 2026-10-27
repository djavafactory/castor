package com.djavafactory.castor.core.port.api;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.port.domain.DataSourceCode;
import com.djavafactory.castor.core.port.infrastructure.OsmImportJob;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Contract tests of the port endpoints. */
@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class PortResourceIT {

    @Inject
    OsmImportJob importJob;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void importSample() {
        importJob.run("westports", DataSourceCode.OSM, "fixtures/osm-sample.geojson", false);
    }

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().executeUpdate("delete from ref.reference_feature");
            c.createStatement().executeUpdate("delete from ref.import_batch");
        }
    }

    @Test
    void listsThePorts() {
        given().when()
                .get("/api/v1/ports")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("code", equalTo(java.util.List.of("westports")))
                .body("[0].name", equalTo("Westports"))
                .body("[0].size()", equalTo(2));
    }

    @Test
    void returnsThePortWithItsFrameAndSources() {
        given().when()
                .get("/api/v1/ports/westports")
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("private, max-age=300"))
                .header("ETag", notNullValue())
                .body("code", equalTo("westports"))
                .body("name", equalTo("Westports"))
                .body("city", equalTo("Port Klang"))
                .body("timeZone", equalTo("Asia/Kuala_Lumpur"))
                .body("utcOffset", equalTo("+08:00"))
                .body("origin.lon", equalTo(101.3066304f))
                .body("origin.lat", equalTo(2.9534057f))
                .body("measureSrid", equalTo(3380))
                .body("frameRotDeg", equalTo(-120.299f))
                .body("extent.type", equalTo("Polygon"))
                .body("landExtent.type", equalTo("Polygon"))
                .body("quayLine.type", equalTo("LineString"))
                .body("synthetic", equalTo(true))
                .body("publishedLayoutVersionId", nullValue())
                .body("berthCount", equalTo(0))
                .body("attribution", containsString("OpenStreetMap"))
                .body("dataSources", hasSize(8))
                .body("dataSources.find { it.code == 'GOOGLE' }.mayPersist", equalTo(false))
                .body("dataSources.find { it.code == 'GOOGLE' }.mayTrace", equalTo(false))
                .body("dataSources.find { it.code == 'SENTINEL2_EOX' }.mayTrace", equalTo(true))
                .body("dataSources.find { it.code == 'OSM' }.licence", containsString("ODbL"));
    }

    @Test
    void portCodeIsCaseInsensitive() {
        given().when().get("/api/v1/ports/WESTPORTS").then().statusCode(200).body("code", equalTo("westports"));
    }

    @Test
    void unknownPortIsAProblemDetails404() {
        given().when()
                .get("/api/v1/ports/nowhere")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("title", equalTo("Not found"))
                .body("detail", containsString("nowhere"));
        given().when().get("/api/v1/ports/nowhere/control-points").then().statusCode(404);
        given().when().get("/api/v1/ports/nowhere/reference-features").then().statusCode(404);
    }

    @Test
    void matchingEtagGives304WithoutBody() {
        for (String path : new String[] {
            "/api/v1/ports",
            "/api/v1/ports/westports",
            "/api/v1/ports/westports/control-points",
            "/api/v1/ports/westports/reference-features"
        }) {
            String etag =
                    given().when().get(path).then().statusCode(200).extract().header("ETag");
            given().header("If-None-Match", etag)
                    .when()
                    .get(path)
                    .then()
                    .statusCode(304)
                    .header("ETag", equalTo(etag))
                    .header("Cache-Control", equalTo("private, max-age=300"))
                    .body(equalTo(""));
            given().header("If-None-Match", "\"stale\"").when().get(path).then().statusCode(200);
        }
    }

    @Test
    void controlPointsAreGeoJsonPoints() {
        given().when()
                .get("/api/v1/ports/westports/control-points")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("type", equalTo("FeatureCollection"))
                .body("features", hasSize(4))
                .body("features[0].geometry.type", equalTo("Point"))
                .body("features.properties.code", hasItem("main-gate"))
                .body("features.find { it.properties.code == 'main-gate' }.properties.expected_local_x", equalTo(-7.9f))
                .body(
                        "features.find { it.properties.code == 'main-gate' }.properties.expected_local_y",
                        equalTo(346.9f))
                .body("features.find { it.properties.code == 'main-gate' }.properties.accuracy", equalTo("APPROXIMATE"))
                .body("features.find { it.properties.code == 'main-gate' }.properties.name", equalTo("Main gate"));
    }

    @Test
    void referenceFeaturesCanBeFilteredByKind() {
        given().when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(200)
                .body("type", equalTo("FeatureCollection"))
                .body("features", hasSize(4));
        given().queryParam("kind", "BUILDING")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(200)
                .body("features", hasSize(2))
                .body("features[0].geometry.type", equalTo("Polygon"))
                .body("features[0].properties.kind", equalTo("BUILDING"))
                .body("features[0].properties.name", equalTo("Shed A"))
                .body("features[0].properties.height_m", equalTo(12.0f))
                .body("features[0].properties.width_m", nullValue());
        given().queryParam("kind", "ROAD")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .body("features", hasSize(2))
                .body("features[0].properties.width_m", equalTo(10.0f));
        given().queryParam("kind", "COASTLINE")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .body("features", empty());
    }

    @Test
    void referenceFeaturesCanBeFilteredByBoundingBox() {
        given().queryParam("bbox", "101.3068,2.9528,101.3076,2.9535")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(200)
                .body("features.properties.osm_ref", hasItem("way/1001"))
                .body("features.properties.osm_ref", org.hamcrest.Matchers.not(hasItem("way/1002")));
        given().queryParam("bbox", "101.0,2.0,101.1,2.1")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .body("features", empty());
    }

    @Test
    void badKindOrBoundingBoxIs400() {
        given().queryParam("kind", "CASTLE")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("detail", equalTo("Unknown kind 'CASTLE'"));
        given().queryParam("bbox", "1,2,3")
                .when()
                .get("/api/v1/ports/westports/reference-features")
                .then()
                .statusCode(400)
                .body("detail", endsWith("minLon,minLat,maxLon,maxLat"));
    }

    @Test
    void responsesAreGzippedWhenAsked() {
        given().header("Accept-Encoding", "gzip")
                .when()
                .get("/api/v1/ports/westports")
                .then()
                .statusCode(200)
                .header("Content-Encoding", equalTo("gzip"));
    }
}
