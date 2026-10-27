package com.djavafactory.castor.core.layout.api;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** DESIGNER-01 parts 1 and 2: draft lifecycle and the audit trail, against the seeded Westports layout. */
@QuarkusTest
class DraftLifecycleIT {

    private static final String DRAFTS = "/api/v1/ports/westports/layouts";

    @Inject
    LayoutSeedJob seed;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper json;

    @Inject
    AuditService audit;

    @BeforeEach
    void seedWestports() {
        LayoutTestSupport.clear(dataSource);
        seed.seed();
    }

    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private long auditRows(String where) throws SQLException {
        try (var c = dataSource.getConnection()) {
            return LayoutTestSupport.scalar(c, "select count(*) from audit.audit_log where " + where, Long.class);
        }
    }

    private JsonNode createDraft(String basedOn) throws Exception {
        return json.readTree(given().contentType(ContentType.JSON)
                .body(Map.of("basedOn", basedOn))
                .post(DRAFTS)
                .then()
                .statusCode(201)
                .extract()
                .asByteArray());
    }

    private static Map<String, String> geometries(JsonNode layout) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode f : layout.get("features").get("features")) {
            out.put(f.get("id").asText(), f.get("geometry").toString());
        }
        return out;
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void draftBasedOnPublishedHasTheSameAssetIdsAndGeometry() throws Exception {
        var published = json.readTree(given().get(DRAFTS + "/current")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());

        var draft = createDraft("published");

        assertEquals("DRAFT", draft.get("layout").get("status").asText());
        assertEquals(2, draft.get("layout").get("versionNo").asInt());
        assertEquals(1, draft.get("layout").get("basedOnVersionNo").asInt());
        assertEquals("alice", draft.get("layout").get("createdBy").asText());
        assertEquals(geometries(published), geometries(draft));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void blankDraftHasNoAssetsAndLeavesThePortAlone() throws Exception {
        String before = given().get("/api/v1/ports/westports").then().extract().asString();

        var draft = createDraft("blank");

        assertEquals(0, draft.get("features").get("features").size());
        assertNull(draft.get("layout").get("basedOnVersionNo").numberValue());
        assertEquals(
                before, given().get("/api/v1/ports/westports").then().extract().asString());
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void draftCanStartFromAnOlderVersion() throws Exception {
        String v1 = given().get(DRAFTS + "/current")
                .then()
                .statusCode(200)
                .extract()
                .path("layout.id");
        try (var c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate("update layout.layout_version set status = 'SUPERSEDED' where version_no = 1");
        }

        var draft = createDraft(v1);

        assertEquals(1, draft.get("layout").get("basedOnVersionNo").asInt());
        assertEquals(301, draft.get("features").get("features").size());
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void secondDraftIsRefusedWithTheIdOfTheFirstAndNoExtraAuditRow() throws Exception {
        var first = createDraft("published");

        given().contentType(ContentType.JSON)
                .body(Map.of("basedOn", "published"))
                .post(DRAFTS)
                .then()
                .statusCode(409)
                .body("detail", equalTo("A draft already exists for this layout"))
                .body("draftId", equalTo(first.get("layout").get("id").asText()));

        assertEquals(1, auditRows("action = 'DRAFT_CREATED'"));
        given().get(DRAFTS + "/draft")
                .then()
                .statusCode(200)
                .body("layout.id", equalTo(first.get("layout").get("id").asText()));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void badRequestsAreRefused() {
        given().contentType(ContentType.JSON).body("{}").post(DRAFTS).then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body(Map.of("basedOn", "nonsense"))
                .post(DRAFTS)
                .then()
                .statusCode(400);
        given().contentType(ContentType.JSON)
                .body(Map.of("basedOn", "00000000-0000-0000-0000-000000000000"))
                .post(DRAFTS)
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "vic", roles = "VIEWER")
    void viewerCannotWrite() throws Exception {
        given().contentType(ContentType.JSON)
                .body(Map.of("basedOn", "published"))
                .post(DRAFTS)
                .then()
                .statusCode(403);
        given().get(DRAFTS + "/draft").then().statusCode(403);

        assertEquals(0, auditRows("true"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void editorCannotDiscard() throws Exception {
        String id = createDraft("published").get("layout").get("id").asText();

        given().delete("/api/v1/layouts/" + id).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void discardKeepsRowsWritesAuditAndAllowsANewDraft() throws Exception {
        String id = createDraft("published").get("layout").get("id").asText();

        given().delete("/api/v1/layouts/" + id).then().statusCode(204);

        given().get(DRAFTS + "/draft").then().statusCode(404);
        given().get("/api/v1/layouts/" + id)
                .then()
                .statusCode(200)
                .body("layout.status", equalTo("DISCARDED"))
                .body("features.features", hasSize(301));
        assertEquals(1, auditRows("action = 'DRAFT_DISCARDED' and layout_version_id = '" + id + "'"));

        var again = createDraft("published");
        assertEquals(3, again.get("layout").get("versionNo").asInt());
        given().get(DRAFTS + "/draft")
                .then()
                .statusCode(200)
                .body("layout.id", equalTo(again.get("layout").get("id").asText()));
    }

    @Test
    @TestSecurity(user = "pat", roles = "PUBLISHER")
    void onlyADraftCanBeDiscarded() {
        String published = given().get(DRAFTS + "/current").then().extract().path("layout.id");

        given().delete("/api/v1/layouts/" + published).then().statusCode(409);
        given().delete("/api/v1/layouts/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void rolledBackChangeWritesNoAuditRow() throws Exception {
        var draft = createDraft("published");
        var id = java.util.UUID.fromString(draft.get("layout").get("id").asText());
        long before = auditRows("true");

        QuarkusTransaction.begin();
        audit.record(AuditAction.ASSET_UPDATED, "ASSET", "x", id, null, json.createObjectNode());
        QuarkusTransaction.rollback();

        assertEquals(before, auditRows("true"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void auditRequiresATransaction() {
        var id = java.util.UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertThrows(
                jakarta.transaction.TransactionalException.class,
                () -> audit.record(AuditAction.ASSET_UPDATED, "ASSET", "x", id, null, null));
    }

    @Test
    @TestSecurity(user = "alice", roles = "EDITOR")
    void auditEndpointIsAdminOnly() {
        given().get("/api/v1/ports/westports/audit").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "root", roles = "ADMIN")
    void auditListsNewestFirstWithFiltersAndKeysetPaging() throws Exception {
        String id = createDraft("published").get("layout").get("id").asText();
        given().delete("/api/v1/layouts/" + id).then().statusCode(204);
        createDraft("blank");

        var all = given().get("/api/v1/ports/westports/audit")
                .then()
                .statusCode(200)
                .body("items", hasSize(3))
                .body("items[0].action", equalTo("DRAFT_CREATED"))
                .body("items[1].action", equalTo("DRAFT_DISCARDED"))
                .body("items[0].actor", equalTo("root"))
                .body("items[0].after.status", equalTo("DRAFT"))
                .body("items[1].before.status", equalTo("DRAFT"))
                .body("items[1].after.status", equalTo("DISCARDED"))
                .body("items[0].requestId", notNullValue())
                .body("nextBefore", org.hamcrest.Matchers.nullValue());

        given().queryParam("limit", 2)
                .get("/api/v1/ports/westports/audit")
                .then()
                .body("items", hasSize(2))
                .body("nextBefore", notNullValue());
        long next = given().queryParam("limit", 2)
                .get("/api/v1/ports/westports/audit")
                .then()
                .extract()
                .jsonPath()
                .getLong("nextBefore");
        given().queryParam("limit", 2)
                .queryParam("before", next)
                .get("/api/v1/ports/westports/audit")
                .then()
                .body("items", hasSize(1))
                .body("items[0].action", equalTo("DRAFT_CREATED"));

        given().queryParam("action", "DRAFT_DISCARDED")
                .get("/api/v1/ports/westports/audit")
                .then()
                .body("items", hasSize(1));
        given().queryParam("actor", "nobody")
                .get("/api/v1/ports/westports/audit")
                .then()
                .body("items", hasSize(0));
        given().queryParam("assetId", id)
                .get("/api/v1/ports/westports/audit")
                .then()
                .body("items", hasSize(2));
        given().queryParam("from", "2999-01-01T00:00:00Z")
                .get("/api/v1/ports/westports/audit")
                .then()
                .body("items", hasSize(0));
        given().queryParam("action", "NOPE")
                .get("/api/v1/ports/westports/audit")
                .then()
                .statusCode(400);
        given().queryParam("limit", 0)
                .get("/api/v1/ports/westports/audit")
                .then()
                .statusCode(400);
        given().queryParam("from", "yesterday")
                .get("/api/v1/ports/westports/audit")
                .then()
                .statusCode(400);
        given().get("/api/v1/ports/nowhere/audit").then().statusCode(404);
        assertEquals(3, all.extract().jsonPath().getList("items").size());
    }
}
