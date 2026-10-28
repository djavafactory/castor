package com.djavafactory.castor.core.layout.api;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
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

import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGNER-10: GET /api/v1/layouts/{id}/export and POST /api/v1/layouts/{id}/import with the known fixture files. */
@QuarkusTest
@TestSecurity(user = "editor", roles = "EDITOR")
class GeoJsonExchangeIT {

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper json;

    @Inject
    LayoutSeedJob seed;

    private UUID draftId;

    @BeforeEach
    void blankDraft() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        try (Connection c = dataSource.getConnection()) {
            draftId = LayoutTestSupport.version(c, 1, "DRAFT");
        }
    }

    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private static byte[] fixture(String name) throws IOException {
        try (var in = GeoJsonExchangeIT.class.getResourceAsStream("/geojson-import/" + name)) {
            return in.readAllBytes();
        }
    }

    private Response upload(UUID layout, String fileName, byte[] content, boolean dryRun, int status) {
        return given().multiPart("file", fileName, content, "application/geo+json")
                .queryParam("dryRun", dryRun)
                .post("/api/v1/layouts/" + layout + "/import")
                .then()
                .statusCode(status)
                .extract()
                .response();
    }

    private Response importFixture(String name, boolean dryRun) throws IOException {
        return upload(draftId, name, fixture(name), dryRun, 200);
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.scalar(c, sql, Long.class);
        }
    }

    private long audit(String action) throws SQLException {
        return count("select count(*) from audit.audit_log where action = '" + action + "'");
    }

    private long revisions(UUID layout) throws SQLException {
        return count("select count(*) from layout.asset_revision where layout_version_id = '" + layout + "'");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Known fixtures
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void validFileDryRunStoresNothingAndTheRealImportStoresEverything() throws Exception {
        importFixture("valid.geojson", true)
                .then()
                .body("file", equalTo("valid.geojson"))
                .body("added", equalTo(3))
                .body("updated", equalTo(0))
                .body("skipped", equalTo(0))
                .body("skipReasons", hasSize(0))
                .body("roundTrip.coordinates", equalTo(5 + 2 + 1));
        assertEquals(0, revisions(draftId));
        assertEquals(0, audit("LAYOUT_IMPORTED"));
        assertEquals(0, audit("ASSET_CREATED"));

        importFixture("valid.geojson", false)
                .then()
                .body("added", equalTo(3))
                .body("roundTrip.maxDifferenceMm", lessThanOrEqualTo(10f));
        assertEquals(3, revisions(draftId));
        assertEquals(1, audit("LAYOUT_IMPORTED"));
        assertEquals(3, audit("ASSET_CREATED"));
    }

    @Test
    void importedAssetsGetTypesProvenanceAndHeadingFromTheFile() throws Exception {
        importFixture("valid.geojson", false);

        JsonNode layout = json.readTree(given().get("/api/v1/layouts/" + draftId)
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
        Map<String, JsonNode> byName = new HashMap<>();
        layout.at("/features/features")
                .forEach(f -> byName.put(f.at("/properties/name").asText(), f));
        assertEquals(
                "LAYDOWN_YARD", byName.get("North yard").at("/properties/type").asText());
        assertEquals("FENCE", byName.get("East fence").at("/properties/type").asText());
        JsonNode gantry = byName.get("Gantry one");
        assertEquals("RTG", gantry.at("/properties/type").asText());
        assertEquals(30, gantry.at("/properties/headingDeg").asDouble(), 0.001);
        // no provenance in the file: the import default
        JsonNode provenance = byName.get("North yard").at("/properties/provenance");
        assertEquals("SURVEY", provenance.get("source").asText());
        assertEquals("VERIFIED", provenance.get("accuracy").asText());
        assertEquals("Imported from GeoJSON", provenance.get("note").asText());
        assertTrue(provenance.get("capturedOn").asText().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void selfIntersectingShapeIsSkippedWithItsReason() throws Exception {
        importFixture("self-intersecting.geojson", true)
                .then()
                .body("added", equalTo(1))
                .body("skipped", equalTo(1))
                .body("skipReasons", hasSize(1))
                .body("skipReasons[0]", startsWith("Bow tie: "));
    }

    @Test
    void shapeOverlappingAnEarlierShapeOfTheSameFileIsSkipped() throws Exception {
        importFixture("overlapping.geojson", false)
                .then()
                .body("added", equalTo(1))
                .body("skipped", equalTo(1))
                .body("skipReasons[0]", startsWith("Shed two: "));
        assertEquals(1, revisions(draftId));
    }

    @Test
    void unknownTypeFallsBackByGeometryAndOtherwiseIsSkipped() throws Exception {
        importFixture("unknown-type.geojson", false)
                .then()
                .body("added", equalTo(1))
                .body("skipped", equalTo(1))
                .body("skipReasons[0]", equalTo("Skipped a Point without a Castor type"));
        assertEquals(1, count("select count(*) from layout.asset where type_code = 'LAYDOWN_YARD'"));
    }

    @Test
    void sourceThatMayNotBeStoredIsRefused() throws Exception {
        importFixture("google-source.geojson", false)
                .then()
                .body("added", equalTo(1))
                .body("skipped", equalTo(1))
                .body("skipReasons[0]", equalTo("Traced yard: Google Maps data cannot be stored"));

        JsonNode layout = json.readTree(
                given().get("/api/v1/layouts/" + draftId).then().extract().asByteArray());
        JsonNode kept = layout.at("/features/features/0/properties/provenance");
        assertEquals("SURVEY", kept.get("source").asText());
        assertEquals("SURVEYED", kept.get("accuracy").asText());
        assertEquals("2026-01-15", kept.get("capturedOn").asText());
        assertEquals("Total station", kept.get("note").asText());
    }

    @Test
    void fileThatIsNotJsonIsRefused() throws Exception {
        upload(draftId, "broken.geojson", fixture("not-json.geojson"), true, 400)
                .then()
                .body("detail", equalTo("That file is not valid JSON"));
    }

    @Test
    void aSingleFeatureIsAccepted() throws Exception {
        JsonNode collection = json.readTree(fixture("valid.geojson"));
        byte[] single = json.writeValueAsBytes(collection.at("/features/0"));

        upload(draftId, "one.json", single, false, 200).then().body("added", equalTo(1));
    }

    @Test
    void otherFileTypesAndRolesAreRefused() throws Exception {
        upload(draftId, "shapes.txt", fixture("valid.geojson"), true, 400);
        upload(draftId, "shapes.geojson", "{\"type\":\"Topology\"}".getBytes(), true, 400)
                .then()
                .body("detail", containsString("FeatureCollection"));
        upload(UUID.randomUUID(), "valid.geojson", fixture("valid.geojson"), true, 404);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void aViewerCannotImport() throws Exception {
        upload(draftId, "valid.geojson", fixture("valid.geojson"), true, 403);
    }

    @Test
    void featureWithTheIdOfAnAssetUpdatesItAndKeepsTheId() throws Exception {
        importFixture("valid.geojson", false);
        JsonNode layout = json.readTree(
                given().get("/api/v1/layouts/" + draftId).then().extract().asByteArray());
        JsonNode yard = null;
        for (JsonNode f : layout.at("/features/features")) {
            if ("North yard".equals(f.at("/properties/name").asText())) {
                yard = f;
            }
        }
        var edited = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(json.writeValueAsString(yard));
        edited.putObject("properties").put("name", "North yard renamed").put("type", "LAYDOWN_YARD");

        upload(draftId, "edit.geojson", json.writeValueAsBytes(edited), false, 200)
                .then()
                .body("added", equalTo(0))
                .body("updated", equalTo(1))
                .body("skipped", equalTo(0));

        assertEquals(3, revisions(draftId));
        given().get("/api/v1/layouts/" + draftId + "/assets/" + yard.get("id").asText())
                .then()
                .statusCode(200)
                .body("properties.name", equalTo("North yard renamed"));
        assertEquals(1, audit("ASSET_UPDATED"));
    }

    @Test
    void importIntoAPublishedVersionIsRefused() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            LayoutTestSupport.clear(dataSource);
            draftId = LayoutTestSupport.version(c, 1, "PUBLISHED");
        }
        upload(draftId, "valid.geojson", fixture("valid.geojson"), true, 409);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Export and the round trip of the Westports layout
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void exportCarriesTheFrameTheVersionAndProvenance() throws Exception {
        importFixture("valid.geojson", false);

        Response export = given().get("/api/v1/layouts/" + draftId + "/export")
                .then()
                .statusCode(200)
                .contentType("application/geo+json")
                .header(
                        "Content-Disposition",
                        matchesPattern("attachment; filename=\"castor-westports-v1-\\d{4}-\\d{2}-\\d{2}\\.geojson\""))
                .extract()
                .response();
        JsonNode body = json.readTree(export.asByteArray());

        assertEquals("FeatureCollection", body.get("type").asText());
        assertEquals("name", body.at("/crs/type").asText());
        assertEquals(
                "urn:ogc:def:crs:OGC:1.3:CRS84", body.at("/crs/properties/name").asText());
        assertEquals(1, body.at("/castor/schemaVersion").asInt());
        assertEquals("westports", body.at("/castor/port").asText());
        assertEquals(101.3066304, body.at("/castor/origin/0").asDouble(), 1e-9);
        assertEquals(2.9534057, body.at("/castor/origin/1").asDouble(), 1e-9);
        assertEquals(-120.299, body.at("/castor/frameRotDeg").asDouble(), 1e-9);
        assertEquals(1, body.at("/castor/versionNo").asInt());
        assertEquals("editor", body.at("/castor/exportedBy").asText());
        assertTrue(body.at("/castor/exportedAt").asText().endsWith("Z"));
        assertTrue(body.at("/castor/measureSrid").isInt());
        assertEquals(3, body.get("features").size());
        body.get("features").forEach(f -> {
            assertTrue(f.at("/properties/provenance/source").isTextual());
            assertTrue(f.at("/properties/provenance/accuracy").isTextual());
        });
    }

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void aViewerCannotExportADraft() {
        given().get("/api/v1/layouts/" + draftId + "/export").then().statusCode(404);
    }

    @Test
    void westportsExportImportedIntoABlankDraftGivesTheSameCoordinates() throws Exception {
        LayoutTestSupport.clear(dataSource);
        seed.seed();
        String published = given().get("/api/v1/ports/westports/layouts/current")
                .then()
                .statusCode(200)
                .extract()
                .path("layout.id");
        byte[] original = given().get("/api/v1/layouts/" + published + "/export")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        JsonNode source = json.readTree(original);

        String blank = given().contentType("application/json")
                .body("{\"basedOn\":\"blank\"}")
                .post("/api/v1/ports/westports/layouts")
                .then()
                .statusCode(201)
                .extract()
                .path("layout.id");
        UUID blankId = UUID.fromString(blank);

        var report = upload(blankId, "westports.geojson", original, false, 200);
        report.then().body("skipped", equalTo(0)).body("roundTrip.maxDifferenceMm", lessThanOrEqualTo(10f));
        assertEquals(source.get("features").size(), (int) report.path("added"));

        JsonNode again = json.readTree(given().get("/api/v1/layouts/" + blank + "/export")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
        assertEquals(source.get("features").size(), again.get("features").size());

        Map<String, JsonNode> byId = new HashMap<>();
        again.get("features").forEach(f -> byId.put(f.get("id").asText(), f));
        double worstMm = 0;
        for (JsonNode f : source.get("features")) {
            JsonNode g = byId.get(f.get("id").asText());
            assertTrue(g != null, "asset " + f.get("id") + " is missing after the import");
            worstMm = Math.max(
                    worstMm,
                    distanceMm(
                            f.get("geometry").get("coordinates"),
                            g.get("geometry").get("coordinates")));
        }
        assertTrue(worstMm <= 10, "largest coordinate difference was " + worstMm + " mm");
    }

    @Test
    void exportOfWestportsHasOneProvenancePerFeatureAndNoGoogleData() throws Exception {
        LayoutTestSupport.clear(dataSource);
        seed.seed();
        String published = given().get("/api/v1/ports/westports/layouts/current")
                .then()
                .extract()
                .path("layout.id");
        JsonNode body = json.readTree(given().get("/api/v1/layouts/" + published + "/export")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("-v1-"))
                .extract()
                .asByteArray());
        assertTrue(body.get("features").size() > 100);
        body.get("features").forEach(f -> {
            String source = f.at("/properties/provenance/source").asText();
            assertTrue(!source.isBlank() && !"GOOGLE".equals(source));
        });
    }

    /** Largest distance in millimetres between two coordinate trees of the same shape. */
    private static double distanceMm(JsonNode a, JsonNode b) {
        if (a.isArray() && a.size() > 0 && a.get(0).isNumber()) {
            double lat = a.get(1).asDouble();
            double dx = (b.get(0).asDouble() - a.get(0).asDouble()) * QuayFrame.metresPerDegreeLon(lat);
            double dy = (b.get(1).asDouble() - a.get(1).asDouble()) * QuayFrame.metresPerDegreeLat(lat);
            return Math.hypot(dx, dy) * 1000;
        }
        double worst = 0;
        for (int i = 0; i < a.size(); i++) {
            worst = Math.max(worst, distanceMm(a.get(i), b.get(i)));
        }
        return worst;
    }
}
