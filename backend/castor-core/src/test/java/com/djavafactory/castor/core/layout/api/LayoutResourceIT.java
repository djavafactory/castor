package com.djavafactory.castor.core.layout.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout read endpoints of CORE-04 part 5 against the seeded Westports layout. */
@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class LayoutResourceIT {

    @Inject
    LayoutSeedJob seed;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper json;

    @BeforeEach
    void seedWestports() {
        LayoutTestSupport.clear(dataSource);
        seed.seed();
    }

    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private JsonNode current() throws Exception {
        return json.readTree(given().when()
                .get("/api/v1/ports/westports/layouts/current")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
    }

    private static JsonNode feature(JsonNode body, String code) {
        for (JsonNode f : body.get("features").get("features")) {
            if (code.equals(f.get("properties").get("code").asText())) {
                return f;
            }
        }
        throw new AssertionError("no feature " + code);
    }

    @Test
    void currentLayoutIsThePublishedVersionWithAFeatureCollection() throws Exception {
        var body = current();

        var layout = body.get("layout");
        assertEquals(1, layout.get("versionNo").asInt());
        assertEquals("PUBLISHED", layout.get("status").asText());
        assertEquals("seed", layout.get("publishedBy").asText());
        assertEquals("Westports demo layout", layout.get("note").asText());
        assertNotNull(layout.get("publishedAt").asText());
        assertEquals("FeatureCollection", body.get("features").get("type").asText());
        assertEquals(301, body.get("features").get("features").size());
    }

    @Test
    void featureShapeHasEveryPropertyOfTheContract() throws Exception {
        var b1 = feature(current(), "B1");

        assertTrue(b1.get("id").asText().matches("[0-9a-f-]{36}"), "id is the asset uuid");
        assertEquals("Feature", b1.get("type").asText());
        assertEquals("LineString", b1.get("geometry").get("type").asText());
        var p = b1.get("properties");
        for (String key : List.of(
                "code",
                "type",
                "name",
                "anchor",
                "headingDeg",
                "lengthM",
                "widthM",
                "heightM",
                "radiusM",
                "lineWidthM",
                "elevationM",
                "storageZoneCode",
                "details",
                "links",
                "provenance",
                "override",
                "computed",
                "rowVersion")) {
            assertTrue(p.has(key), "missing " + key);
        }
        assertEquals("BERTH", p.get("type").asText());
        assertEquals(400.0, p.get("lengthM").asDouble());
        assertEquals(16.5, p.get("details").get("berth").get("depthM").asDouble());
        assertEquals(
                "BREAKBULK",
                p.get("details").get("berth").get("cargoFamilies").get(0).asText());
        assertTrue(p.get("override").isNull());
        assertEquals("SYNTHETIC", p.get("provenance").get("source").asText());
        assertEquals("APPROXIMATE", p.get("provenance").get("accuracy").asText());
        assertEquals("2026-10-21", p.get("provenance").get("capturedOn").asText());
        assertEquals(0, p.get("rowVersion").asInt());
        // computed: a line has a length, not an area; the 400 m berth is about 400 m in the measurement CRS
        var c = p.get("computed");
        assertEquals(400.0, c.get("lengthM").asDouble(), 0.5);
        assertTrue(c.get("areaM2").isNull());
        assertEquals(-390.0, c.get("local").get("x").asDouble(), 0.05);
        assertEquals(0.0, c.get("local").get("y").asDouble(), 0.05);
        assertEquals(-590.0, c.get("chainage").get("startM").asDouble(), 0.05);
        assertEquals(-190.0, c.get("chainage").get("endM").asDouble(), 0.05);
    }

    @Test
    void polygonsReportAreaAndPerimeterAndLinksUsePermanentAssetIds() throws Exception {
        var body = current();
        var pad = feature(body, "PAD-A1");
        assertEquals("Polygon", pad.get("geometry").get("type").asText());
        var computed = pad.get("properties").get("computed");
        assertEquals(124 * 34, computed.get("areaM2").asDouble(), 124 * 34 * 0.002);
        assertEquals(2 * (124 + 34), computed.get("perimeterM").asDouble(), 0.5);
        assertTrue(computed.get("lengthM").isNull());
        var storage = pad.get("properties").get("details");
        assertEquals(2, storage.get("storagePad").get("bays").asInt());
        assertEquals(15120, storage.get("storagePad").get("capacityT").asDouble(), 2.0, "2 slots of 7,560 t of coal");

        var qc1 = feature(body, "QC1");
        var link = qc1.get("properties").get("links").get(0);
        assertEquals("SERVES", link.get("kind").asText());
        assertEquals(
                feature(body, "B1").get("id").asText(), link.get("toAssetId").asText());
    }

    @Test
    void currentLayoutSupportsETagAnd304() {
        Response first = given().when().get("/api/v1/ports/westports/layouts/current");
        String etag = first.getHeader("ETag");
        assertNotNull(etag);

        given().header("If-None-Match", etag)
                .when()
                .get("/api/v1/ports/westports/layouts/current")
                .then()
                .statusCode(304);
        given().header("If-None-Match", "\"stale\"")
                .when()
                .get("/api/v1/ports/westports/layouts/current")
                .then()
                .statusCode(200);
    }

    @Test
    void etagChangesWhenARevisionChanges() throws Exception {
        String before =
                given().when().get("/api/v1/ports/westports/layouts/current").getHeader("ETag");
        UUID draft;
        try (Connection c = dataSource.getConnection()) {
            draft = LayoutTestSupport.version(c, 2, "DRAFT");
            c.createStatement()
                    .execute("select layout.copy_revisions((select id from layout.layout_version "
                            + "where version_no = 1), '" + draft + "')");
            // a draft does not change the published answer
        }
        assertEquals(
                before,
                given().when().get("/api/v1/ports/westports/layouts/current").getHeader("ETag"));
        // publishing something else would change it; a status change of the version row is enough here
        try (Connection c = dataSource.getConnection()) {
            c.createStatement()
                    .execute(
                            "update layout.layout_version set row_version = row_version + 1 " + "where version_no = 1");
        }
        assertTrue(!before.equals(
                given().when().get("/api/v1/ports/westports/layouts/current").getHeader("ETag")));
    }

    @Test
    void anyVersionCanBeReadById() throws Exception {
        String id = current().get("layout").get("id").asText();

        given().when()
                .get("/api/v1/layouts/" + id)
                .then()
                .statusCode(200)
                .body("layout.id", equalTo(id))
                .body("layout.versionNo", equalTo(1))
                .body("features.features", hasSize(301));
        given().when().get("/api/v1/layouts/" + UUID.randomUUID()).then().statusCode(404);
        given().when().get("/api/v1/layouts/not-a-uuid").then().statusCode(404);
    }

    @Test
    void oneAssetIsReadWithAnETagEqualToItsRowVersion() throws Exception {
        var body = current();
        String layoutId = body.get("layout").get("id").asText();
        String assetId = feature(body, "QC1").get("id").asText();

        Response r = given().when().get("/api/v1/layouts/" + layoutId + "/assets/" + assetId);
        r.then().statusCode(200).body("type", equalTo("Feature")).body("properties.code", equalTo("QC1"));
        assertEquals("\"0\"", r.getHeader("ETag"));
        given().header("If-None-Match", "\"0\"")
                .when()
                .get("/api/v1/layouts/" + layoutId + "/assets/" + assetId)
                .then()
                .statusCode(304);
        given().when()
                .get("/api/v1/layouts/" + layoutId + "/assets/" + UUID.randomUUID())
                .then()
                .statusCode(404);
    }

    @Test
    void viewersNeverSeeDrafts() throws SQLException {
        given().when().get("/api/v1/ports/westports/layouts/draft").then().statusCode(403); // viewers never see drafts
    }

    @Test
    @TestSecurity(
            user = "editor",
            roles = {"EDITOR", "VIEWER"})
    void draftIs404WithoutOneAndReadableWithOne() throws SQLException {
        given().when()
                .get("/api/v1/ports/westports/layouts/draft")
                .then()
                .statusCode(404)
                .body("status", equalTo(404));

        UUID draft;
        try (Connection c = dataSource.getConnection()) {
            draft = LayoutTestSupport.version(c, 2, "DRAFT");
            c.createStatement()
                    .execute("select layout.copy_revisions((select id from layout.layout_version "
                            + "where version_no = 1), '" + draft + "')");
        }
        given().when()
                .get("/api/v1/ports/westports/layouts/draft")
                .then()
                .statusCode(200)
                .body("layout.status", equalTo("DRAFT"))
                .body("layout.versionNo", equalTo(2))
                .body("features.features", hasSize(301));
    }

    @Test
    void aViewerCannotReadADraftById() throws SQLException {
        UUID draft;
        try (Connection c = dataSource.getConnection()) {
            draft = LayoutTestSupport.version(c, 2, "DRAFT");
        }
        given().when().get("/api/v1/layouts/" + draft).then().statusCode(404);
    }

    @Test
    @TestSecurity(
            user = "editor",
            roles = {"EDITOR", "VIEWER"})
    void coordinatesReadBackIdenticalToTheStoredOnesToOneTenMillionthOfADegree() throws Exception {
        // save through the database, then GET: the response must carry exactly the stored vertices
        UUID draft;
        UUID asset;
        double[][] ring = {
            {101.3066304, 2.9534057},
            {101.3067304, 2.9534057},
            {101.3067304, 2.9535057},
            {101.3066304, 2.9535057},
            {101.3066304, 2.9534057}
        };
        try (Connection c = dataSource.getConnection()) {
            draft = LayoutTestSupport.version(c, 2, "DRAFT");
            asset = LayoutTestSupport.asset(c, "WH9", "BUILDING");
            var sb = new StringBuilder("POLYGON((");
            for (int i = 0; i < ring.length; i++) {
                sb.append(i == 0 ? "" : ", ").append(ring[i][0]).append(' ').append(ring[i][1]);
            }
            LayoutTestSupport.revision(
                    c, asset, draft, "Hall 9", sb.append("))").toString());
        }

        var f = json.readTree(given().when()
                .get("/api/v1/layouts/" + draft + "/assets/" + asset)
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());

        var coords = f.get("geometry").get("coordinates").get(0);
        assertEquals(ring.length, coords.size());
        for (int i = 0; i < ring.length; i++) {
            assertEquals(ring[i][0], coords.get(i).get(0).asDouble(), 1e-7);
            assertEquals(ring[i][1], coords.get(i).get(1).asDouble(), 1e-7);
        }
    }

    @Test
    void westportsLayoutIsUnderOnePointFiveMegabytesAndFastEnough() throws Exception {
        byte[] body = given().when()
                .get("/api/v1/ports/westports/layouts/current")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertTrue(body.length < 1_500_000, "uncompressed size " + body.length);

        for (int i = 0; i < 10; i++) { // warm up
            given().when().get("/api/v1/ports/westports/layouts/current").then().statusCode(200);
        }
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long t = System.nanoTime();
            given().when().get("/api/v1/ports/westports/layouts/current").then().statusCode(200);
            millis.add((System.nanoTime() - t) / 1_000_000);
        }
        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertTrue(p95 < 300, "p95 " + p95 + " ms, all " + millis);
    }

    @Test
    void portReportsPublishedLayoutAndBerthCount() {
        given().when()
                .get("/api/v1/ports/westports")
                .then()
                .statusCode(200)
                .body("publishedLayoutVersionId", notNullValue())
                .body("berthCount", equalTo(13));
    }

    @Test
    void unknownPortIs404() {
        given().when().get("/api/v1/ports/nowhere/layouts/current").then().statusCode(404);
        given().when().get("/api/v1/ports/WESTPORTS/layouts/current").then().statusCode(200); // case-insensitive
    }
}
