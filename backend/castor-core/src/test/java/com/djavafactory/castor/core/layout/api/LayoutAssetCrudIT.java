package com.djavafactory.castor.core.layout.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGNER-04 part 1: asset create, update, delete, duplicate, override and re-create in a draft. */
@QuarkusTest
@TestSecurity(user = "editor", roles = "EDITOR")
class LayoutAssetCrudIT {

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper json;

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

    private String assets() {
        return "/api/v1/layouts/" + draftId + "/assets";
    }

    /** A rubber-tyred gantry at a local quay-frame position on land, with the palette defaults. */
    private ObjectNode rtg(double x, double y) {
        var at = LayoutTestSupport.FRAME.toLonLat(x, y);
        ObjectNode body = json.createObjectNode();
        body.put("type", "RTG");
        body.put("name", "RTG");
        body.putArray("anchor").add(at.lon()).add(at.lat());
        body.put("headingDeg", 30);
        body.putObject("provenance")
                .put("source", "OPERATOR")
                .put("accuracy", "APPROXIMATE")
                .put("note", "Placed in the layout designer");
        return body;
    }

    private Response create(ObjectNode body, int status) {
        return given().contentType("application/json")
                .body(body)
                .post(assets())
                .then()
                .statusCode(status)
                .extract()
                .response();
    }

    private JsonNode tree(Response r) throws Exception {
        return json.readTree(r.asByteArray());
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.scalar(c, sql, Long.class);
        }
    }

    private long audit(String action) throws SQLException {
        return count("select count(*) from audit.audit_log where action = '" + action + "'");
    }

    private long revisions() throws SQLException {
        return count("select count(*) from layout.asset_revision where layout_version_id = '" + draftId + "'");
    }

    @Test
    void createReturns201WithLocationEtagFeatureAndGeneratedCode() throws Exception {
        Response r = create(rtg(1000, 100), 201);

        JsonNode body = tree(r);
        JsonNode feature = body.get("feature");
        String id = feature.get("id").asText();
        assertTrue(r.getHeader("Location").endsWith(assets() + "/" + id));
        assertEquals("\"0\"", r.getHeader("ETag"));
        assertEquals("RTG-0001", feature.get("properties").get("code").asText());
        assertEquals(30.0, feature.get("properties").get("headingDeg").asDouble(), 1e-9);
        assertEquals(1, feature.get("properties").get("modelRev").asInt());
        assertTrue(body.get("warnings").isArray());
        assertEquals(1, audit("ASSET_CREATED"));

        // a second one of the same prefix gets the next number
        String second = tree(create(rtg(1100, 100), 201))
                .get("feature")
                .get("properties")
                .get("code")
                .asText();
        assertEquals("RTG-0002", second);
    }

    @Test
    void createdAssetKeepsItsAnchorWithinOneCentimetre() throws Exception {
        ObjectNode body = rtg(1000, 100);
        double lon = body.get("anchor").get(0).asDouble();
        double lat = body.get("anchor").get(1).asDouble();
        create(body, 201);

        JsonNode draft = json.readTree(given().get("/api/v1/ports/westports/layouts/draft")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
        JsonNode stored =
                draft.get("features").get("features").get(0).get("properties").get("anchor");
        assertEquals(lon, stored.get(0).asDouble(), 1e-7);
        assertEquals(lat, stored.get(1).asDouble(), 1e-7);
    }

    @Test
    void coordinatesAreCanonicalisedAndTheAdjustmentIsReported() throws Exception {
        ObjectNode body = rtg(1000, 100);
        var anchor = (com.fasterxml.jackson.databind.node.ArrayNode) body.get("anchor");
        double lon = anchor.get(0).asDouble() + 0.00000004;
        anchor.set(0, json.getNodeFactory().numberNode(lon));
        JsonNode created = tree(create(body, 201));
        double stored =
                created.get("feature").get("properties").get("anchor").get(0).asDouble();
        assertEquals(Math.round(lon * 1e7) / 1e7, stored, 1e-12);

        String id = created.get("feature").get("id").asText();
        ObjectNode patch = json.createObjectNode().put("name", "RTG moved");
        patch.putArray("anchor")
                .add(anchor.get(0).asDouble() + 0.000000049)
                .add(anchor.get(1).asDouble());
        Response r = given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body(patch)
                .patch(assets() + "/" + id)
                .then()
                .statusCode(200)
                .extract()
                .response();
        assertTrue(tree(r).get("adjustmentMm").asDouble() >= 0);
    }

    @Test
    void patchMergesKeepsTheIdAndBumpsTheEtag() throws Exception {
        JsonNode created = tree(create(rtg(1000, 100), 201));
        String id = created.get("feature").get("id").asText();

        Response r = given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body("{\"name\":\"Gantry one\",\"headingDeg\":90}")
                .patch(assets() + "/" + id)
                .then()
                .statusCode(200)
                .extract()
                .response();

        assertEquals("\"1\"", r.getHeader("ETag"));
        JsonNode feature = tree(r).get("feature");
        assertEquals(id, feature.get("id").asText());
        assertEquals("Gantry one", feature.get("properties").get("name").asText());
        assertEquals(90.0, feature.get("properties").get("headingDeg").asDouble(), 1e-9);
        assertEquals(12.0, feature.get("properties").get("lengthM").asDouble(), 1e-9, "untouched fields stay");
        assertEquals(1, audit("ASSET_UPDATED"));
    }

    @Test
    void theDraftEtagChangesWhenAnAssetBelowTheHighestRowVersionIsEdited() throws Exception {
        String first =
                tree(create(rtg(1000, 100), 201)).get("feature").get("id").asText();
        String second =
                tree(create(rtg(1200, 100), 201)).get("feature").get("id").asText();
        for (String version : new String[] {"\"0\"", "\"1\""}) {
            given().contentType("application/json")
                    .header("If-Match", version)
                    .body("{\"headingDeg\":45}")
                    .patch(assets() + "/" + first)
                    .then()
                    .statusCode(200);
        }
        String before = given().get("/api/v1/layouts/" + draftId).getHeader("ETag");

        // row version 0 to 1, below the 2 of the first asset: the count and the highest row version stay the same
        given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body("{\"name\":\"Gantry two\"}")
                .patch(assets() + "/" + second)
                .then()
                .statusCode(200);

        assertNotEquals(before, given().get("/api/v1/layouts/" + draftId).getHeader("ETag"));
        given().header("If-None-Match", before)
                .get("/api/v1/layouts/" + draftId)
                .then()
                .statusCode(200)
                .body(containsString("Gantry two"));
    }

    @Test
    void secondEditorWithAStaleVersionGets409AndNothingIsLost() throws Exception {
        JsonNode created = tree(create(rtg(1000, 100), 201));
        String id = created.get("feature").get("id").asText();
        given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body("{\"name\":\"First editor\"}")
                .patch(assets() + "/" + id)
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body("{\"name\":\"Second editor\"}")
                .patch(assets() + "/" + id)
                .then()
                .statusCode(409);

        assertEquals(
                "First editor",
                count("select count(*) from layout.asset_revision where name = 'First editor'") == 1
                        ? "First editor"
                        : "lost");
        assertEquals(1, audit("ASSET_UPDATED"));
    }

    @Test
    void missingIfMatchIs412() throws Exception {
        String id = tree(create(rtg(1000, 100), 201)).get("feature").get("id").asText();
        given().contentType("application/json")
                .body("{\"name\":\"x\"}")
                .patch(assets() + "/" + id)
                .then()
                .statusCode(412);
        given().delete(assets() + "/" + id).then().statusCode(412);
    }

    @Test
    void invalidPlacementIs422WithTheCodeAndNothingIsStored() throws Exception {
        ObjectNode hall = rtg(1000, 100);
        hall.put("type", "WAREHOUSE").put("name", "Hall");
        JsonNode first = tree(create(hall, 201));
        assertEquals(
                "WH-0001", first.get("feature").get("properties").get("code").asText());
        assertEquals(
                6,
                first.get("feature")
                        .get("properties")
                        .get("details")
                        .get("warehouse")
                        .get("dockDoors")
                        .asInt());
        long audits = audit("ASSET_CREATED");

        given().contentType("application/json")
                .body(hall)
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("OVERLAP"))
                .body("errors[0].message", containsString("Overlaps"));

        assertEquals(1, revisions());
        assertEquals(audits, audit("ASSET_CREATED"));
    }

    @Test
    void geometryFromAFetchOnlySourceIs422() throws Exception {
        ObjectNode body = rtg(1000, 100);
        ((ObjectNode) body.get("provenance")).put("source", "GOOGLE");
        given().contentType("application/json")
                .body(body)
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("TRACE_SOURCE_NOT_ALLOWED"));
        assertEquals(0, revisions());
    }

    @Test
    void onlyDraftsAcceptWrites() throws Exception {
        UUID published;
        try (Connection c = dataSource.getConnection()) {
            published = LayoutTestSupport.version(c, 2, "PUBLISHED");
        }
        given().contentType("application/json")
                .body(rtg(1000, 100))
                .post("/api/v1/layouts/" + published + "/assets")
                .then()
                .statusCode(409)
                .body("detail", containsString("Layout version is not a draft"));
    }

    @Test
    void deleteRemovesTheRevisionAndTheLinksPointingToIt() throws Exception {
        String target =
                tree(create(rtg(1000, 100), 201)).get("feature").get("id").asText();
        ObjectNode other = rtg(1100, 100);
        other.putArray("links").addObject().put("kind", "SERVES").put("toAssetId", target);
        JsonNode linked = tree(create(other, 201));
        assertEquals(1, linked.get("feature").get("properties").get("links").size());

        Response r = given().header("If-Match", "\"0\"")
                .delete(assets() + "/" + target)
                .then()
                .statusCode(204)
                .extract()
                .response();

        assertTrue(r.getHeader("X-Castor-Dependents").contains("RTG-0002"));
        assertEquals(1, revisions());
        assertEquals(0, count("select count(*) from layout.asset_link"));
        assertEquals(1, count("select count(*) from layout.asset where id = '" + target + "'"), "identity is kept");
        assertEquals(1, audit("ASSET_DELETED"));
        given().get(assets() + "/" + target).then().statusCode(404);
    }

    @Test
    void anAssetDeletedInTheDraftCanBeRecreatedWithTheSameId() throws Exception {
        JsonNode created = tree(create(rtg(1000, 100), 201));
        String id = created.get("feature").get("id").asText();
        String code = created.get("feature").get("properties").get("code").asText();
        given().header("If-Match", "\"0\"").delete(assets() + "/" + id).then().statusCode(204);

        ObjectNode again = rtg(1000, 100);
        again.put("id", id).put("code", code);
        JsonNode back = tree(create(again, 201));
        assertEquals(id, back.get("feature").get("id").asText());
        assertEquals(code, back.get("feature").get("properties").get("code").asText());

        // the id exists again in this draft, any other use of it is a conflict
        create(again, 409);
    }

    @Test
    void duplicateOffsetsTwentyMetresAndRenames() throws Exception {
        JsonNode original = tree(create(rtg(1000, 100), 201));
        String id = original.get("feature").get("id").asText();

        Response r = given().post(assets() + "/" + id + "/duplicate")
                .then()
                .statusCode(201)
                .extract()
                .response();

        JsonNode copy = tree(r).get("feature");
        assertNotEquals(id, copy.get("id").asText());
        assertEquals("RTG copy", copy.get("properties").get("name").asText());
        assertEquals("RTG-0002", copy.get("properties").get("code").asText());
        var a = original.get("feature").get("properties").get("computed").get("local");
        var b = copy.get("properties").get("computed").get("local");
        assertEquals(a.get("x").asDouble() + 20, b.get("x").asDouble(), 0.01);
        assertEquals(a.get("y").asDouble() + 20, b.get("y").asDouble(), 0.01);
    }

    @Test
    void overrideNeedsAReasonOfTenToFiveHundredCharactersAndIsAudited() throws Exception {
        String id = tree(create(rtg(1000, 100), 201)).get("feature").get("id").asText();

        given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body("{\"override\":{\"reason\":\"short\"}}")
                .patch(assets() + "/" + id)
                .then()
                .statusCode(422);

        Response r = given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body("{\"override\":{\"reason\":\"Approved by the harbour master on site\"}}")
                .patch(assets() + "/" + id)
                .then()
                .statusCode(200)
                .extract()
                .response();
        assertEquals(
                "Approved by the harbour master on site",
                tree(r).get("feature")
                        .get("properties")
                        .get("override")
                        .get("reason")
                        .asText());
        assertNotNull(r.getHeader("ETag"));
        assertEquals(1, audit("OVERRIDE_RECORDED"));
    }

    @Test
    @TestSecurity(user = "viola", roles = "VIEWER")
    void viewersMayNotWrite() throws Exception {
        given().contentType("application/json")
                .body(rtg(1000, 100))
                .post(assets())
                .then()
                .statusCode(403);
    }

    // ---- CASTOR-30: every property in real units, read back from the stored draft ----

    /** A warehouse with the operator, the description and the warehouse details the inspector sends. */
    private ObjectNode warehouse(double x, double y, String doorSide) {
        ObjectNode body = rtg(x, y);
        body.put("type", "WAREHOUSE").put("name", "Hall");
        body.put("operatorName", "Terminal Ops").put("description", "Cold store, north bay");
        ObjectNode hall = body.putObject("details").putObject("warehouse");
        hall.put("roofRiseM", 2.5).put("dockDoors", 4);
        if (doorSide != null) {
            hall.put("doorSide", doorSide);
        }
        return body;
    }

    /** A storage pad with the given bays, rows, pitches and height, placed like the other test shapes. */
    private ObjectNode pad(double x, double y, int bays, int rows, double bayPitch, double rowPitch, double height) {
        ObjectNode body = rtg(x, y);
        body.put("type", "STORAGE_PAD").put("name", "Pad");
        body.putObject("details")
                .putObject("storagePad")
                .put("bays", bays)
                .put("rows", rows)
                .put("bayPitchM", bayPitch)
                .put("rowPitchM", rowPitch)
                .put("maxHeightM", height);
        return body;
    }

    /** The properties of the draft's feature with this code, read back through GET (not the create response). */
    private JsonNode storedProperties(String code) throws Exception {
        JsonNode draft = json.readTree(given().get("/api/v1/ports/westports/layouts/draft")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
        for (JsonNode f : draft.get("features").get("features")) {
            if (code.equals(f.get("properties").get("code").asText())) {
                return f.get("properties");
            }
        }
        throw new AssertionError("no stored asset " + code);
    }

    @Test
    void operatorDescriptionWarehouseAndPadFieldsSurviveAReload() throws Exception {
        create(warehouse(1000, 100, "EAST"), 201);
        String padCode = tree(create(pad(1000, 400, 2, 1, 62, 34, 10), 201))
                .get("feature")
                .get("properties")
                .get("code")
                .asText();

        JsonNode hall = storedProperties("WH-0001");
        assertEquals("Terminal Ops", hall.get("operatorName").asText());
        assertEquals("Cold store, north bay", hall.get("description").asText());
        JsonNode wh = hall.get("details").get("warehouse");
        assertEquals("EAST", wh.get("doorSide").asText());
        assertEquals(4, wh.get("dockDoors").asInt());
        assertEquals(2.5, wh.get("roofRiseM").asDouble(), 1e-9);

        JsonNode pitch = storedProperties(padCode).get("details").get("storagePad");
        assertEquals(2, pitch.get("bays").asInt());
        assertEquals(62, pitch.get("bayPitchM").asDouble(), 1e-9);
        assertEquals(34, pitch.get("rowPitchM").asDouble(), 1e-9);
    }

    @Test
    void aWarehouseWithoutADoorSideFacesNorth() throws Exception {
        JsonNode created = tree(create(warehouse(1000, 100, null), 201));
        assertEquals(
                "NORTH",
                created.get("feature")
                        .get("properties")
                        .get("details")
                        .get("warehouse")
                        .get("doorSide")
                        .asText());
        assertEquals(
                "NORTH",
                storedProperties("WH-0001")
                        .get("details")
                        .get("warehouse")
                        .get("doorSide")
                        .asText());
    }

    @Test
    void patchChangesTheDoorSideAndTheNewSideSurvivesAReload() throws Exception {
        String id = tree(create(warehouse(1000, 100, "NORTH"), 201))
                .get("feature")
                .get("id")
                .asText();

        Response r = given().contentType("application/json")
                .header("If-Match", "\"0\"")
                .body("{\"details\":{\"warehouse\":{\"doorSide\":\"SOUTH\"}}}")
                .patch(assets() + "/" + id)
                .then()
                .statusCode(200)
                .extract()
                .response();
        assertEquals("\"1\"", r.getHeader("ETag"));
        assertEquals(
                "SOUTH",
                tree(r).get("feature")
                        .get("properties")
                        .get("details")
                        .get("warehouse")
                        .get("doorSide")
                        .asText());

        JsonNode stored = storedProperties("WH-0001").get("details").get("warehouse");
        assertEquals("SOUTH", stored.get("doorSide").asText());
        assertEquals(4, stored.get("dockDoors").asInt(), "the other warehouse fields stay");
    }

    @Test
    void anUnknownDoorSideIs422AndNothingIsStored() throws Exception {
        given().contentType("application/json")
                .body(warehouse(1000, 100, "UP"))
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("DOOR_SIDE_INVALID"))
                .body("errors[0].pointer", equalTo("/properties/details/warehouse/doorSide"));
        assertEquals(0, revisions());
    }

    @Test
    void aPadBayPitchBelowFiveMetresIs422() throws Exception {
        given().contentType("application/json")
                .body(pad(1000, 400, 2, 1, 4, 34, 10))
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("DIM_OUT_OF_RANGE"))
                .body("errors[0].message", equalTo("Bay pitch: enter a number of at least 5"));
        assertEquals(0, revisions());
    }

    @Test
    void aPadWithNoBaysIs422NotAServerError() throws Exception {
        given().contentType("application/json")
                .body(pad(1000, 400, 0, 1, 62, 34, 10))
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].message", equalTo("Bays: enter a number of at least 1"));
        assertEquals(0, revisions());
    }

    @Test
    void aValueTheDatabaseRefusesIs422NotAServerError() throws Exception {
        ObjectNode hall = warehouse(1000, 100, "NORTH");
        ((ObjectNode) hall.get("details").get("warehouse")).put("dockDoors", -1);
        given().contentType("application/json")
                .body(hall)
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("DETAILS_INVALID"));
        assertEquals(0, revisions());
    }

    @Test
    void aBerthDepthBelowOneMetreIs422() throws Exception {
        ObjectNode berth = rtg(0, 0);
        berth.put("type", "BERTH").put("name", "Berth 1");
        berth.putObject("details").putObject("berth").put("depthM", 0.5).putArray("cargoFamilies");
        given().contentType("application/json")
                .body(berth)
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("DIM_OUT_OF_RANGE"))
                .body("errors[0].message", equalTo("Depth: enter a number of at least 1"));
        assertEquals(0, revisions());
    }

    @Test
    void aBerthShorterThanTwentyMetresIs422() throws Exception {
        ObjectNode berth = rtg(0, 0);
        berth.put("type", "BERTH").put("name", "Berth 1").put("lengthM", 10);
        given().contentType("application/json")
                .body(berth)
                .post(assets())
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("DIM_OUT_OF_RANGE"))
                .body("errors[0].message", equalTo("Length: enter a number of at least 20"));
        assertEquals(0, revisions());
    }
}
