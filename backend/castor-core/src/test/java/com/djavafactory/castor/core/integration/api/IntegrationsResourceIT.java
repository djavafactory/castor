package com.djavafactory.castor.core.integration.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.shared.domain.GatewayRefusedException;
import com.djavafactory.castor.core.shared.domain.GatewayUnavailableException;
import com.djavafactory.castor.core.support.FakeIntegrationGateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Integrations API of core: who may look and who may act, that every call goes to the gateway for the right port and
 * path, and that every action leaves exactly one audit row (never with an API key in it).
 */
@QuarkusTest
class IntegrationsResourceIT {

    private static final String BASE = "/api/v1/ports/westports/integrations";
    private static final String RECEPTION = "0198a6f0-0000-7000-8000-000000000001";

    @Inject
    FakeIntegrationGateway gateway;

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clean() throws SQLException {
        gateway.reset();
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().executeUpdate("delete from audit.audit_log where action like 'INTEGRATION_%'");
        }
    }

    private long auditRows() throws SQLException {
        try (Connection c = dataSource.getConnection();
                var rs = c.createStatement()
                        .executeQuery("select count(*) from audit.audit_log where action like 'INTEGRATION_%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String audit(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Every endpoint of the API as a request: the method, the path and a body where one is needed. */
    private record Endpoint(String method, String path, boolean acts) {}

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("GET", "/sources", false),
            new Endpoint("GET", "/sources/cbas/keys", false),
            new Endpoint("GET", "/quarantine", false),
            new Endpoint("GET", "/quarantine/" + RECEPTION, false),
            new Endpoint("GET", "/mappings", false),
            new Endpoint("GET", "/mappings/unresolved", false),
            new Endpoint("POST", "/quarantine/" + RECEPTION + "/replay", true),
            new Endpoint("POST", "/sources/cbas/keys", true),
            new Endpoint("POST", "/sources/cbas/keys/7/rotate", true),
            new Endpoint("DELETE", "/sources/cbas/keys/7", true),
            new Endpoint("POST", "/mappings", true),
            new Endpoint("POST", "/mappings/3/close", true));

    private static io.restassured.response.Response call(Endpoint e) {
        var request = given().contentType(ContentType.JSON).body("{\"sourceSystem\":\"cbas\"}");
        return switch (e.method()) {
            case "GET" -> request.get(BASE + e.path());
            case "DELETE" -> request.delete(BASE + e.path());
            default -> request.post(BASE + e.path());
        };
    }

    // --- access ---

    @Test
    void nobodyWhoIsNotSignedInGetsAnything() {
        for (Endpoint e : ENDPOINTS) {
            call(e).then().statusCode(401);
        }
        assertTrue(gateway.calls().isEmpty());
    }

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void aViewerGets403OnEveryEndpointAndTheGatewayIsNotAsked() throws SQLException {
        for (Endpoint e : ENDPOINTS) {
            call(e).then().statusCode(403);
        }
        assertTrue(gateway.calls().isEmpty());
        assertEquals(0, auditRows());
    }

    @Test
    @TestSecurity(
            user = "editor",
            roles = {"EDITOR", "PUBLISHER"})
    void editorsAndPublishersAreNotOperators() {
        for (Endpoint e : ENDPOINTS) {
            call(e).then().statusCode(403);
        }
        assertTrue(gateway.calls().isEmpty());
    }

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void anOperatorSeesEverythingButCannotActAndNothingIsAudited() throws SQLException {
        for (Endpoint e : ENDPOINTS) {
            call(e).then().statusCode(e.acts() ? 403 : 200);
        }

        assertEquals(6, gateway.calls().size(), "only the six reads reached the gateway");
        assertTrue(gateway.calls().stream().allMatch(c -> c.method().equals("GET")));
        assertEquals(0, auditRows());
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void anAdminMayDoEverything() {
        for (Endpoint e : ENDPOINTS) {
            call(e).then().statusCode(org.hamcrest.Matchers.lessThan(300));
        }
    }

    // --- reads are proxied to the gateway ---

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void readsAskTheGatewayForThePortInTheUrlAndReturnItsJson() {
        gateway.reply("GET", "/sources", "{\"items\":[{\"code\":\"cbas\",\"accepted\":4}]}");

        given().get(BASE + "/sources")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items[0].code", equalTo("cbas"))
                .body("items[0].accepted", equalTo(4));
        // a port code is case-insensitive in URLs, as everywhere in core
        given().get("/api/v1/ports/WestPorts/integrations/sources").then().statusCode(200);

        assertEquals("westports", gateway.calls().get(0).portId());
        assertEquals("westports", gateway.calls().get(1).portId());
    }

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void filtersAndPagingReachTheGatewayAndBlankOnesDoNot() {
        given().queryParam("source", "sensors")
                .queryParam("reason", "UNKNOWN_MAPPING")
                .queryParam("before", 42)
                .queryParam("limit", 10)
                .queryParam("junk", "x")
                .get(BASE + "/quarantine")
                .then()
                .statusCode(200);
        given().queryParam("source", " ").get(BASE + "/quarantine").then().statusCode(200);
        given().queryParam("source", "itap")
                .queryParam("entityType", "BERTH")
                .queryParam("sourceId", "B9")
                .queryParam("state", "open")
                .queryParam("limit", 5)
                .get(BASE + "/mappings")
                .then()
                .statusCode(200);

        var calls = gateway.calls();
        assertEquals(
                java.util.Map.of("source", "sensors", "reason", "UNKNOWN_MAPPING", "before", "42", "limit", "10"),
                calls.get(0).query());
        assertEquals(java.util.Map.of(), calls.get(1).query());
        assertEquals(
                java.util.Map.of(
                        "source", "itap", "entityType", "BERTH", "sourceId", "B9", "state", "open", "limit", "5"),
                calls.get(2).query());
    }

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void aQuarantinedRecordAndItsKeysAndMappingsComeFromTheGateway() {
        gateway.reply("GET", "/quarantine/" + RECEPTION, "{\"entry\":{\"reasonCode\":\"UNKNOWN_MAPPING\"},\"raw\":{}}");
        gateway.reply("GET", "/sources/cbas/keys", "{\"items\":[{\"id\":7,\"prefix\":\"ck_abcde\"}]}");
        gateway.reply("GET", "/mappings/unresolved", "{\"items\":[{\"sourceId\":\"B9\",\"records\":3}]}");

        given().get(BASE + "/quarantine/" + RECEPTION).then().body("entry.reasonCode", equalTo("UNKNOWN_MAPPING"));
        given().get(BASE + "/sources/cbas/keys").then().body("items[0].prefix", equalTo("ck_abcde"));
        given().get(BASE + "/mappings/unresolved").then().body("items[0].records", equalTo(3));
    }

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void anUnknownPortIsNotFoundAndTheGatewayIsNotAsked() {
        given().get("/api/v1/ports/nowhere/integrations/sources").then().statusCode(404);

        assertTrue(gateway.calls().isEmpty());
    }

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void aMalformedReceptionIdOrSourceNeverReachesTheGateway() {
        given().get(BASE + "/quarantine/not-a-uuid").then().statusCode(400);
        given().get(BASE + "/sources/..%2F..%2Fsecret/keys")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(400), equalTo(404)));
        given().get(BASE + "/sources/CBAS/keys").then().statusCode(400);

        assertTrue(gateway.calls().isEmpty());
    }

    // --- the gateway's answers ---

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void aRefusalOfTheGatewayKeepsItsStatusAndCode() {
        gateway.fail(
                "GET",
                "/quarantine/" + RECEPTION,
                new GatewayRefusedException(404, "NOT_IN_QUARANTINE", "No quarantined record"));

        given().get(BASE + "/quarantine/" + RECEPTION)
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("errors[0].code", equalTo("NOT_IN_QUARANTINE"))
                .body("detail", equalTo("No quarantined record"));
    }

    @Test
    @TestSecurity(user = "operator", roles = "OPERATOR")
    void aGatewayThatCannotBeReachedIs502WithoutItsDetails() {
        gateway.fail("GET", "/sources", new GatewayUnavailableException("connect to castor-dev-gateway:8082 failed"));

        given().get(BASE + "/sources")
                .then()
                .statusCode(502)
                .contentType("application/problem+json")
                .body("title", equalTo("Ingest gateway unavailable"))
                .body(not(containsString("castor-dev-gateway")));
    }

    // --- actions are audited ---

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void aReplayIsAuditedOnceWithWhoAndWhichRecord() throws SQLException {
        gateway.reply(
                "POST",
                "/quarantine/" + RECEPTION + "/replay",
                "{\"receptionId\":\"new-1\",\"replayOf\":\"" + RECEPTION + "\"}");

        given().post(BASE + "/quarantine/" + RECEPTION + "/replay")
                .then()
                .statusCode(201)
                .body("receptionId", equalTo("new-1"))
                .body("replayOf", equalTo(RECEPTION));

        assertEquals(1, auditRows());
        assertEquals("admin", audit("select actor from audit.audit_log where action = 'INTEGRATION_REPLAYED'"));
        assertEquals(RECEPTION, audit("select entity_id from audit.audit_log where action = 'INTEGRATION_REPLAYED'"));
        assertEquals(
                "INTEGRATION_RECORD",
                audit("select entity_kind from audit.audit_log where action = 'INTEGRATION_REPLAYED'"));
        assertEquals(
                "new-1",
                audit("select after ->> 'receptionId' from audit.audit_log where action = 'INTEGRATION_REPLAYED'"));
        assertEquals(
                "westports",
                audit("select p.code from audit.audit_log a join ref.port p on p.id = a.port_id "
                        + "where a.action = 'INTEGRATION_REPLAYED'"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void theAuditTrailOfTheKeyActionsNeverHoldsTheKeyAndTheResponseIsNotCached() throws SQLException {
        String secret = "ck_THISisTHEsecretKEYshownONCE0123456789abc";
        gateway.reply(
                "POST",
                "/sources/cbas/keys",
                "{\"id\":9,\"prefix\":\"ck_THISi\",\"origin\":\"issued\",\"createdAt\":\"2026-10-29T08:00:00Z\",\"key\":\""
                        + secret + "\"}");
        gateway.reply(
                "POST",
                "/sources/cbas/keys/9/rotate",
                "{\"id\":10,\"prefix\":\"ck_NEWKE\",\"origin\":\"issued\",\"createdAt\":\"2026-10-29T09:00:00Z\",\"key\":\"ck_NEWkeyNEWkeyNEWkeyNEWkeyNEWkeyNEWkey1234\"}");
        gateway.reply(
                "DELETE",
                "/sources/cbas/keys/10",
                "{\"id\":10,\"prefix\":\"ck_NEWKE\",\"origin\":\"issued\",\"revokedAt\":\"2026-10-29T10:00:00Z\"}");

        given().post(BASE + "/sources/cbas/keys")
                .then()
                .statusCode(201)
                .header("Cache-Control", equalTo("no-store"))
                .body("key", equalTo(secret));
        given().post(BASE + "/sources/cbas/keys/9/rotate")
                .then()
                .statusCode(201)
                .header("Cache-Control", equalTo("no-store"));
        given().delete(BASE + "/sources/cbas/keys/10")
                .then()
                .statusCode(200)
                .body("revokedAt", containsString("10:00"));

        assertEquals(3, auditRows());
        assertEquals("cbas/9", audit("select entity_id from audit.audit_log where action = 'INTEGRATION_KEY_CREATED'"));
        assertEquals(
                "ck_THISi",
                audit("select after ->> 'prefix' from audit.audit_log where action = 'INTEGRATION_KEY_CREATED'"));
        assertEquals("cbas/9", audit("select entity_id from audit.audit_log where action = 'INTEGRATION_KEY_ROTATED'"));
        assertEquals(
                "cbas/10", audit("select entity_id from audit.audit_log where action = 'INTEGRATION_KEY_REVOKED'"));
        assertEquals(
                "0",
                audit("select count(*) from audit.audit_log where action like 'INTEGRATION_%' "
                        + "and (before::text like '%ck_%key%' or after::text like '%\"key\"%' or after::text like '%NEWkey%' "
                        + "or after::text like '%THISis%')"),
                "no row holds a key");
    }

    @Test
    @TestSecurity(user = "boss", roles = "ADMIN")
    void aMappingIsCreatedAsTheSignedInUserAndAuditedAndClosedAndAudited() throws SQLException {
        gateway.reply(
                "POST",
                "/mappings",
                "{\"id\":3,\"sourceSystem\":\"cbas\",\"sourceId\":\"B9\",\"canonicalId\":\"asset-9\",\"effectiveTo\":null}");
        gateway.reply(
                "POST", "/mappings/3/close", "{\"id\":3,\"sourceId\":\"B9\",\"effectiveTo\":\"2026-10-29T10:00:00Z\"}");

        given().contentType(ContentType.JSON)
                .body("""
                        {"sourceSystem":"cbas","sourceEntityType":"BERTH","sourceId":"B9","canonicalId":"asset-9",
                         "effectiveFrom":"2026-10-29T08:00:00Z"}""")
                .post(BASE + "/mappings")
                .then()
                .statusCode(201)
                .body("id", equalTo(3));
        given().contentType(ContentType.JSON)
                .body("{\"effectiveTo\":\"2026-10-29T10:00:00Z\"}")
                .post(BASE + "/mappings/3/close")
                .then()
                .statusCode(200)
                .body("effectiveTo", equalTo("2026-10-29T10:00:00Z"));

        var created = gateway.calls().get(0).body();
        assertEquals("boss", created.get("actor").asText(), "core says who acts; the browser cannot choose it");
        assertEquals("B9", created.get("sourceId").asText());
        assertEquals("2026-10-29T08:00:00Z", created.get("effectiveFrom").asText());
        assertEquals(
                "2026-10-29T10:00:00Z",
                gateway.calls().get(1).body().get("effectiveTo").asText());
        assertEquals(2, auditRows());
        assertEquals("boss", audit("select actor from audit.audit_log where action = 'INTEGRATION_MAPPING_CREATED'"));
        assertEquals("3", audit("select entity_id from audit.audit_log where action = 'INTEGRATION_MAPPING_CREATED'"));
        assertEquals(
                "asset-9",
                audit(
                        "select after ->> 'canonicalId' from audit.audit_log where action = 'INTEGRATION_MAPPING_CREATED'"));
        assertEquals("3", audit("select entity_id from audit.audit_log where action = 'INTEGRATION_MAPPING_CLOSED'"));
    }

    @Test
    @TestSecurity(user = "boss", roles = "ADMIN")
    void theBrowserCannotNameTheActorOfAMapping() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"sourceSystem":"cbas","sourceEntityType":"BERTH","sourceId":"B9","canonicalId":"x","actor":"someone-else"}""")
                .post(BASE + "/mappings")
                .then()
                .statusCode(201);

        assertEquals("boss", gateway.calls().get(0).body().get("actor").asText());
    }

    @Test
    @TestSecurity(user = "boss", roles = "ADMIN")
    void anActionTheGatewayRefusesIsNotAuditedAndKeepsTheGatewaysAnswer() throws SQLException {
        gateway.fail(
                "POST",
                "/quarantine/" + RECEPTION + "/replay",
                new GatewayRefusedException(409, "RAW_EXPIRED", "The payload of this record was removed"));
        gateway.fail(
                "POST", "/mappings", new GatewayRefusedException(409, "MAPPING_OVERLAP", "Another mapping is valid"));
        gateway.fail("DELETE", "/sources/cbas/keys/7", new GatewayUnavailableException("down"));

        given().post(BASE + "/quarantine/" + RECEPTION + "/replay")
                .then()
                .statusCode(409)
                .body("errors[0].code", equalTo("RAW_EXPIRED"));
        given().contentType(ContentType.JSON)
                .body("{\"sourceSystem\":\"cbas\"}")
                .post(BASE + "/mappings")
                .then()
                .statusCode(409)
                .body("errors[0].code", equalTo("MAPPING_OVERLAP"));
        given().delete(BASE + "/sources/cbas/keys/7").then().statusCode(502);

        assertEquals(0, auditRows());
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void theAuditEndpointListsIntegrationActionsLikeAnyOther() {
        gateway.reply("POST", "/quarantine/" + RECEPTION + "/replay", "{\"receptionId\":\"x\"}");
        given().post(BASE + "/quarantine/" + RECEPTION + "/replay").then().statusCode(201);

        given().get("/api/v1/ports/westports/audit?action=INTEGRATION_REPLAYED")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].action", equalTo("INTEGRATION_REPLAYED"))
                .body("items[0].actor", equalTo("admin"))
                .body("items[0].entityId", equalTo(RECEPTION))
                .body("items[0].layoutVersionId", org.hamcrest.Matchers.nullValue());
    }
}
