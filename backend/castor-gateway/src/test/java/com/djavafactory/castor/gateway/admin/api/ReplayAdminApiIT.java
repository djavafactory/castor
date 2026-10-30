package com.djavafactory.castor.gateway.admin.api;

import java.time.Duration;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.support.AdminSupport;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The internal admin API as castor-core calls it: the service token, the counters of the sources, the quarantine with
 * masked secrets, API keys and identity mappings.
 */
@QuarkusTest
class ReplayAdminApiIT extends AdminSupport {

    @AfterEach
    void removeIssuedKeys() {
        Db.execute(dataSource, "delete from ingest.api_key where origin = 'issued'");
    }

    // --- the service token ---

    @Test
    void everyInternalPathRefusesARequestWithoutTheServiceToken() {
        for (String path : new String[] {"/sources", "/quarantine", "/mappings", "/mappings/unresolved"}) {
            given().get(ADMIN + path)
                    .then()
                    .statusCode(401)
                    .contentType("application/problem+json")
                    .header("WWW-Authenticate", "Bearer");
            given().header("Authorization", "Bearer not-the-token")
                    .get(ADMIN + path)
                    .then()
                    .statusCode(401);
            given().header("Authorization", "Basic " + serviceToken)
                    .get(ADMIN + path)
                    .then()
                    .statusCode(401);
            given().header("X-Castor-Key", demoKey).get(ADMIN + path).then().statusCode(401);
        }
        given().post(ADMIN + "/quarantine/00000000-0000-7000-8000-000000000000/replay")
                .then()
                .statusCode(401);
    }

    @Test
    void theServiceTokenOpensTheAdminApiAndThePublicIntakeKeepsItsOwnKey() {
        admin().get(ADMIN + "/sources").then().statusCode(200);

        // the service token is not an intake key and the intake key is not a service token
        post(Events.EVENTS_PATH, serviceToken, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(401);
        given().header("Authorization", "Bearer " + demoKey)
                .get(ADMIN + "/sources")
                .then()
                .statusCode(401);
        post(Events.status(Events.newSourceEventId())).then().statusCode(202);
    }

    @Test
    void anUnknownInternalPathIsNotFound() {
        admin().get("/internal/v1/nothing").then().statusCode(404);
    }

    // --- sources and counters ---

    @Test
    void theSourcesShowAcceptedDuplicateAndQuarantinedOfTheLastDayAndTheirFreshness() {
        String good = Events.newSourceEventId();
        accept(good);
        post(Events.status(good)).then().statusCode(200);
        post(Events.status(good)).then().statusCode(200);
        post(Events.invalidStatus(Events.newSourceEventId())).then().statusCode(202);
        normalizer.runOnce();

        admin().get(ADMIN + "/sources")
                .then()
                .statusCode(200)
                .body(
                        "items.code",
                        org.hamcrest.Matchers.hasItems("canonical", "cbas", "itap", "scss", "edic", "sensors"))
                .body("items.find { it.code == 'canonical' }.accepted", is(2))
                .body("items.find { it.code == 'canonical' }.duplicate", is(2))
                .body("items.find { it.code == 'canonical' }.quarantined", is(1))
                .body("items.find { it.code == 'canonical' }.lastReceivedAt", equalTo("2026-10-29T08:00:00Z"))
                .body("items.find { it.code == 'canonical' }.freshness", equalTo("FRESH"))
                .body("items.find { it.code == 'canonical' }.activeKeys", is(1))
                .body("items.find { it.code == 'canonical' }.enabled", is(true))
                .body("items.find { it.code == 'canonical' }.qualityDefault", equalTo("SIMULATED"))
                .body("items.find { it.code == 'sensors' }.accepted", is(0))
                .body("items.find { it.code == 'sensors' }.lastReceivedAt", nullValue())
                .body("items.find { it.code == 'sensors' }.freshness", equalTo("UNKNOWN"));
    }

    @Test
    void aSourceBecomesStaleAfterItsSlaAndTheCountersForgetWhatIsOlderThanADay() {
        accept(Events.newSourceEventId());
        clock.advance(Duration.ofMinutes(14));
        admin().get(ADMIN + "/sources")
                .then()
                .body("items.find { it.code == 'canonical' }.freshness", equalTo("FRESH"));

        clock.advance(Duration.ofMinutes(2));
        admin().get(ADMIN + "/sources")
                .then()
                .body("items.find { it.code == 'canonical' }.freshness", equalTo("STALE"))
                .body("items.find { it.code == 'canonical' }.accepted", is(1));

        clock.advance(Duration.ofHours(24));
        admin().get(ADMIN + "/sources")
                .then()
                .body("items.find { it.code == 'canonical' }.accepted", is(0))
                .body("items.find { it.code == 'canonical' }.lastReceivedAt", equalTo("2026-10-29T08:00:00Z"));
    }

    @Test
    void aSourcesOwnSlaDecidesWhenItIsStale() {
        Db.execute(
                dataSource,
                "update ingest.integration_source set freshness_sla = '{\"a\": \"PT1M\", \"b\": \"PT2H\"}' "
                        + "where code = 'canonical'");
        try {
            accept(Events.newSourceEventId());
            clock.advance(Duration.ofHours(1));

            admin().get(ADMIN + "/sources")
                    .then()
                    .body("items.find { it.code == 'canonical' }.freshness", equalTo("FRESH"))
                    .body("items.find { it.code == 'canonical' }.freshnessSlaSeconds", equalTo(7200));
        } finally {
            Db.execute(
                    dataSource, "update ingest.integration_source set freshness_sla = '{}' where code = 'canonical'");
        }
    }

    @Test
    void aPortWithoutSourcesHasAnEmptyList() {
        admin().get("/internal/v1/ports/otherport/sources")
                .then()
                .statusCode(200)
                .body("items", empty());
        admin().get("/internal/v1/ports/Bad_Port/sources")
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("INVALID_PORT"));
    }

    // --- quarantine ---

    @Test
    void theQuarantineDetailMasksKeysAndSecretsAndLeavesTheStoredPayloadAlone() {
        String id = Events.newSourceEventId();
        String body = Events.invalidStatus(id)
                .replace(
                        "\"quality\"",
                        "\"apiKey\": \"ck_0123456789abcdef0123456789\", \"nested\": {\"password\": \"hunter2\", "
                                + "\"note\": \"sent with Bearer abcdefgh12345678 yesterday\"}, \"quality\"");
        String receptionId = post(body).then().statusCode(202).extract().path("receptionId");
        normalizer.runOnce();

        var response = admin().get(ADMIN + "/quarantine/" + receptionId).then().statusCode(200);
        response.body("entry.reasonCode", equalTo("SCHEMA_INVALID"))
                .body("entry.sourceEventId", equalTo(id))
                .body("rawAvailable", is(true))
                .body("raw.apiKey", equalTo("[REDACTED]"))
                .body("raw.nested.password", equalTo("[REDACTED]"))
                .body("raw.nested.note", equalTo("sent with [REDACTED] yesterday"))
                .body("raw.sourceEventId", equalTo(id))
                .body("raw.payload.status", equalTo("BROKEN"))
                .body("attempts", empty());
        assertEquals(
                "ck_0123456789abcdef0123456789",
                Db.value(
                        dataSource,
                        "select raw ->> 'apiKey' from ingest.inbox where reception_id = ?::uuid",
                        receptionId),
                "the stored payload is not changed");
        admin().get(ADMIN + "/quarantine/" + receptionId).then().body(not(containsString("hunter2")));
        admin().get(ADMIN + "/quarantine").then().body(not(containsString("hunter2")));
    }

    @Test
    void theListFiltersBySourceAndReasonAndPagesByKeyset() {
        quarantinedGps("GPS-A", "2026-10-29T09:00:00Z");
        quarantinedGps("GPS-B", "2026-10-29T09:01:00Z");
        post(Events.invalidStatus(Events.newSourceEventId())).then().statusCode(202);
        normalizer.runOnce();

        admin().get(ADMIN + "/quarantine").then().body("items", hasSize(3));
        admin().get(ADMIN + "/quarantine?source=sensors")
                .then()
                .body("items", hasSize(2))
                .body("items.source", org.hamcrest.Matchers.everyItem(equalTo("sensors")));
        admin().get(ADMIN + "/quarantine?reason=SCHEMA_INVALID")
                .then()
                .body("items", hasSize(1))
                .body("items[0].source", equalTo("canonical"));
        admin().get(ADMIN + "/quarantine?source=cbas").then().body("items", empty());

        Integer next = admin().get(ADMIN + "/quarantine?limit=2")
                .then()
                .body("items", hasSize(2))
                .body("nextBefore", notNullValue())
                .extract()
                .path("nextBefore");
        admin().get(ADMIN + "/quarantine?limit=2&before=" + next)
                .then()
                .body("items", hasSize(1))
                .body("nextBefore", nullValue());
        admin().get(ADMIN + "/quarantine?limit=0").then().statusCode(422);
        admin().get(ADMIN + "/quarantine?source=nosuch").then().statusCode(422);
    }

    @Test
    void aUnknownQuarantineRecordIsNotFound() {
        admin().get(ADMIN + "/quarantine/00000000-0000-7000-8000-000000000000")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }

    // --- API keys ---

    @Test
    void aNewKeyIsShownOnceWorksAtIntakeAndIsListedWithoutItsSecret() {
        String key = admin().post(ADMIN + "/sources/canonical/keys")
                .then()
                .statusCode(201)
                .body("key", matchesPattern("ck_.{43}"))
                .body("prefix", notNullValue())
                .body("origin", equalTo("issued"))
                .body("revokedAt", nullValue())
                .extract()
                .path("key");

        post(Events.EVENTS_PATH, key, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(202);
        admin().get(ADMIN + "/sources/canonical/keys")
                .then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items[0].prefix", equalTo(key.substring(0, 8)))
                .body("items[0].key", nullValue())
                .body("items.origin", org.hamcrest.Matchers.hasItems("issued", "env"))
                .body(not(containsString(key)));
        assertEquals(
                1,
                Db.count(
                        dataSource,
                        "select count(*) from ingest.api_key where key_hash = sha256(?::bytea)",
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "only the hash is stored");
    }

    @Test
    void rotatingAKeyRevokesTheOldOneAtOnceAndIssuesANewOne() {
        String old =
                admin().post(ADMIN + "/sources/canonical/keys").then().extract().path("key");
        int oldId =
                admin().get(ADMIN + "/sources/canonical/keys").then().extract().path("items[0].id");

        String fresh = admin().post(ADMIN + "/sources/canonical/keys/" + oldId + "/rotate")
                .then()
                .statusCode(201)
                .body("key", matchesPattern("ck_.{43}"))
                .extract()
                .path("key");

        post(Events.EVENTS_PATH, old, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(401);
        post(Events.EVENTS_PATH, fresh, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(202);
        admin().get(ADMIN + "/sources/canonical/keys")
                .then()
                .body("items.find { it.id == " + oldId + " }.revokedAt", notNullValue());
        admin().post(ADMIN + "/sources/canonical/keys/" + oldId + "/rotate")
                .then()
                .statusCode(409)
                .body("errors[0].code", equalTo("KEY_REVOKED"));
    }

    @Test
    void aRevokedKeyIsRefusedAndRevokingTwiceIsAConflict() {
        String key =
                admin().post(ADMIN + "/sources/canonical/keys").then().extract().path("key");
        int id = admin().get(ADMIN + "/sources/canonical/keys").then().extract().path("items[0].id");

        admin().delete(ADMIN + "/sources/canonical/keys/" + id)
                .then()
                .statusCode(200)
                .body("revokedAt", notNullValue())
                .body("key", nullValue());

        post(Events.EVENTS_PATH, key, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(401);
        admin().delete(ADMIN + "/sources/canonical/keys/" + id).then().statusCode(409);
    }

    @Test
    void theDemoKeyOfTheEnvironmentCannotBeRotatedOrRevokedHere() {
        int envKey = admin().get(ADMIN + "/sources/canonical/keys")
                .then()
                .extract()
                .path("items.find { it.origin == 'env' }.id");

        admin().delete(ADMIN + "/sources/canonical/keys/" + envKey)
                .then()
                .statusCode(409)
                .body("errors[0].code", equalTo("KEY_FROM_ENVIRONMENT"));
        admin().post(ADMIN + "/sources/canonical/keys/" + envKey + "/rotate")
                .then()
                .statusCode(409);
        post(Events.status(Events.newSourceEventId())).then().statusCode(202);
    }

    @Test
    void aKeyOfAnotherSourceOrAnUnknownSourceIsNotFoundOrInvalid() {
        int canonicalKey =
                admin().get(ADMIN + "/sources/canonical/keys").then().extract().path("items[0].id");

        admin().delete(ADMIN + "/sources/cbas/keys/" + canonicalKey).then().statusCode(404);
        admin().get(ADMIN + "/sources/nosuch/keys").then().statusCode(422);
        admin().post("/internal/v1/ports/otherport/sources/cbas/keys").then().statusCode(404);
    }

    // --- identity mappings ---

    @Test
    void anAdminCreatesListsAndClosesAMapping() {
        int id = mapSensor("GPS-QC9", "asset-qc9")
                .then()
                .statusCode(201)
                .body("sourceSystem", equalTo("sensors"))
                .body("sourceId", equalTo("GPS-QC9"))
                .body("canonicalId", equalTo("asset-qc9"))
                .body("effectiveFrom", equalTo("1970-01-01T00:00:00Z"))
                .body("effectiveTo", nullValue())
                .body("createdBy", equalTo("admin"))
                .extract()
                .path("id");

        admin().get(ADMIN + "/mappings?source=sensors&entityType=EQUIPMENT&sourceId=qc9&state=open")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].id", equalTo(id));
        admin().get(ADMIN + "/mappings?state=closed").then().body("items", empty());

        admin().body("{\"effectiveTo\": \"2026-10-29T10:00:00Z\"}")
                .post(ADMIN + "/mappings/" + id + "/close")
                .then()
                .statusCode(200)
                .body("effectiveTo", equalTo("2026-10-29T10:00:00Z"));
        admin().get(ADMIN + "/mappings?state=closed").then().body("items", hasSize(1));
        admin().get(ADMIN + "/mappings?state=open").then().body("items", empty());
        admin().body("{}").post(ADMIN + "/mappings/" + id + "/close").then().statusCode(409);
    }

    @Test
    void aMappingThatOverlapsAnotherOfTheSameIdIsAConflictAndAClosedOneMakesRoomForANewOne() {
        int first =
                mapSensor("GPS-QC8", "asset-1").then().statusCode(201).extract().path("id");

        mapSensor("GPS-QC8", "asset-2").then().statusCode(409).body("errors[0].code", equalTo("MAPPING_OVERLAP"));

        admin().body("{\"effectiveTo\": \"2026-10-29T10:00:00Z\"}")
                .post(ADMIN + "/mappings/" + first + "/close")
                .then()
                .statusCode(200);
        admin().body("""
                        {"sourceSystem":"sensors","sourceEntityType":"EQUIPMENT","sourceId":"GPS-QC8",
                         "canonicalId":"asset-2","effectiveFrom":"2026-10-29T10:00:00Z","actor":"admin"}""").post(ADMIN + "/mappings").then().statusCode(201);
        admin().get(ADMIN + "/mappings?sourceId=GPS-QC8").then().body("items", hasSize(2));
    }

    @Test
    void aMappingMustBeCompleteAndMakeSense() {
        admin().body("{}").post(ADMIN + "/mappings").then().statusCode(422);
        admin().body("""
                        {"sourceSystem":"nosuch","sourceEntityType":"EQUIPMENT","sourceId":"x","canonicalId":"y","actor":"a"}""")
                .post(ADMIN + "/mappings")
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("INVALID_SOURCESYSTEM"));
        admin().body("""
                        {"sourceSystem":"sensors","sourceEntityType":"EQUIPMENT","sourceId":" ","canonicalId":"y","actor":"a"}""").post(ADMIN + "/mappings").then().statusCode(422).body("errors[0].code", equalTo("REQUIRED"));
        admin().body("""
                        {"sourceSystem":"sensors","sourceEntityType":"EQUIPMENT","sourceId":"x","canonicalId":"y"}""").post(ADMIN + "/mappings").then().statusCode(422);
        admin().body("""
                        {"sourceSystem":"sensors","sourceEntityType":"EQUIPMENT","sourceId":"x","canonicalId":"y",
                         "effectiveFrom":"2026-10-29T10:00:00Z","effectiveTo":"2026-10-29T09:00:00Z","actor":"a"}""")
                .post(ADMIN + "/mappings")
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("END_BEFORE_START"));
        admin().body("{\"effectiveTo\": \"1960-01-01T00:00:00Z\"}")
                .post(ADMIN + "/mappings/999999/close")
                .then()
                .statusCode(404);
        mapSensor("GPS-QC7", "asset").then().statusCode(201);
        int id = admin().get(ADMIN + "/mappings?sourceId=GPS-QC7")
                .then()
                .extract()
                .path("items[0].id");
        admin().body("{\"effectiveTo\": \"1960-01-01T00:00:00Z\"}")
                .post(ADMIN + "/mappings/" + id + "/close")
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("END_BEFORE_START"));
        admin().get(ADMIN + "/mappings?state=sideways").then().statusCode(422);
        assertEquals(1, Db.count(dataSource, "select count(*) from ingest.identity_mapping"));
    }

    @Test
    void unresolvedIdsAreCountedPerSourceIdAndSplitByReason() {
        quarantinedGps("GPS-ONE", "2026-10-29T09:00:00Z");
        quarantinedGps("GPS-ONE", "2026-10-29T09:01:00Z");
        quarantinedGps("GPS-TWO", "2026-10-29T09:02:00Z");
        mapAsset("sensors", "EQUIPMENT", "GPS-AMB", "asset-1");
        Db.execute(dataSource, "alter table ingest.identity_mapping drop constraint identity_mapping_no_overlap");
        try {
            mapAsset("sensors", "EQUIPMENT", "GPS-AMB", "asset-2");
            quarantinedGps("GPS-AMB", "2026-10-29T09:03:00Z");

            admin().get(ADMIN + "/mappings/unresolved")
                    .then()
                    .statusCode(200)
                    .body("items", hasSize(3))
                    .body("items[0].sourceId", equalTo("GPS-ONE"))
                    .body("items[0].records", is(2))
                    .body("items[0].reasonCode", equalTo("UNKNOWN_MAPPING"))
                    .body("items.find { it.sourceId == 'GPS-TWO' }.records", is(1))
                    .body("items.find { it.sourceId == 'GPS-AMB' }.reasonCode", equalTo("AMBIGUOUS_MAPPING"));
        } finally {
            Db.execute(dataSource, "delete from ingest.identity_mapping where source_id = 'GPS-AMB'");
            Db.execute(
                    dataSource,
                    "alter table ingest.identity_mapping add constraint identity_mapping_no_overlap exclude using gist "
                            + "(port_id with =, source_system with =, source_entity_type with =, source_id with =, "
                            + "tstzrange(effective_from, effective_to) with &&)");
        }
    }
}
