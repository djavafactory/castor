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
}
