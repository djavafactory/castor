package com.djavafactory.castor.gateway.intake.infrastructure;

import java.net.URL;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.intake.domain.ApiKeys;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;
import com.djavafactory.castor.gateway.shared.infrastructure.DataResetService;
import com.djavafactory.castor.gateway.shared.infrastructure.Jdbc;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.LivePipeline;
import com.djavafactory.castor.gateway.support.PipelineSupport;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Start-up: the sources and keys written from .env, the reset that leaves schema ingest empty, health and metrics. */
@QuarkusTest
@TestProfile(LivePipeline.class)
class IntakeBootstrapIT extends PipelineSupport {

    @Inject
    DataResetService reset;

    @Inject
    DemoKeyBootstrap bootstrap;

    @Inject
    Jdbc jdbc;

    @Inject
    CastorConfig castor;

    @TestHTTPResource(value = "/health/ready", management = true)
    URL ready;

    @TestHTTPResource(value = "/health/live", management = true)
    URL live;

    @TestHTTPResource(value = "/metrics", management = true)
    URL metrics;

    @TestHTTPResource(value = "/openapi", management = true)
    URL openapi;

    @AfterEach
    void restoreTheDemoKey() {
        bootstrap.run();
    }

    private CastorConfig config(String env, String portCode, String demoKey) {
        var values = new java.util.HashMap<String, String>(Map.of("castor.env", env, "castor.port-code", portCode));
        if (demoKey != null) {
            values.put("castor.intake.demo-key", demoKey);
        }
        return new SmallRyeConfigBuilder()
                .withMapping(CastorConfig.class)
                .withDefaultValues(values)
                .build()
                .getConfigMapping(CastorConfig.class);
    }

    private long activeEnvKeys(String hash) {
        return Db.count(dataSource, "select count(*) from ingest.api_key where origin = 'env' and revoked_at is null");
    }

    @Test
    void everySourceOfThePortExistsAndTheDemoKeyIsStoredHashedOnEach() {
        List<String> codes = Db.column(
                dataSource, "select code from ingest.integration_source where port_id = 'westports' order by code");
        assertEquals(List.of("canonical", "cbas", "edic", "itap", "scss", "sensors"), codes);

        assertEquals(
                List.of("canonical"),
                Db.column(dataSource, "select code from ingest.integration_source where enabled"),
                "only canonical has an adapter, the system sources stay off until INGEST-03");
        assertEquals(
                "SIMULATED", Db.value(dataSource, "select distinct quality_default from ingest.integration_source"));
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.integration_source where require_signature"));

        assertEquals(6, activeEnvKeys(null));
        byte[] stored = Db.value(
                dataSource, "select distinct key_hash from ingest.api_key where origin = 'env' and revoked_at is null");
        assertArrayEquals(ApiKeys.hash(demoKey), stored);
        assertEquals(
                ApiKeys.prefix(demoKey),
                Db.value(
                        dataSource,
                        "select distinct prefix from ingest.api_key where origin = 'env' and revoked_at is null"));
        assertEquals(
                0,
                Db.count(
                        dataSource,
                        "select count(*) from ingest.api_key where convert_from(key_hash, 'LATIN1') like ?",
                        "%" + demoKey + "%"));
    }

    @Test
    void schemaIngestHasItsOwnFlywayHistoryAtVersion1() {
        assertEquals(
                List.of("1"),
                Db.column(
                        dataSource,
                        "select version from ingest.flyway_schema_history where success and version is not null"));
    }

    @Test
    void resetEmptiesEveryTableThatHoldsEventsAndBootstrapsAgain() {
        accept(Events.newSourceEventId());
        post(Events.invalidStatus(Events.newSourceEventId())).then().statusCode(202);
        await().atMost(java.time.Duration.ofSeconds(30))
                .until(() -> Db.count(dataSource, "select count(*) from ingest.inbox where status = 'RECEIVED'") == 0);
        assertEquals(2, Db.count(dataSource, "select count(*) from ingest.inbox"));
        assertEquals(1, Db.count(dataSource, "select count(*) from ingest.quarantine"));
        Db.execute(dataSource, "update ingest.integration_source set enabled = true where code = 'cbas'");

        reset.reset();

        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.inbox"));
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.outbox"));
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.quarantine"));
        assertEquals(
                6,
                Db.count(dataSource, "select count(*) from ingest.integration_source"),
                "sources are bootstrapped again");
        assertEquals(
                List.of("canonical"),
                Db.column(dataSource, "select code from ingest.integration_source where enabled"),
                "a change made after start is gone");
        post(Events.status(Events.newSourceEventId())).then().statusCode(202);
    }

    @Test
    void anotherDemoKeyRevokesTheOldOneAndTheOldOneComesBackWhenRestored() {
        String second = "a-second-demo-key-for-the-test";

        new DemoKeyBootstrap(config("test", "westports", second), jdbc, clock).run();

        post(Events.EVENTS_PATH, demoKey, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(401);
        post(Events.EVENTS_PATH, second, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(202);
        assertEquals(6, activeEnvKeys(null), "one demo key per source, never two");

        bootstrap.run();

        post(Events.EVENTS_PATH, demoKey, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(202);
        post(Events.EVENTS_PATH, second, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(401);
    }

    @Test
    void runningTheBootstrapAgainChangesNothing() {
        long sources = Db.count(dataSource, "select count(*) from ingest.integration_source");
        long keys = Db.count(dataSource, "select count(*) from ingest.api_key");

        bootstrap.run();
        bootstrap.run();

        assertEquals(sources, Db.count(dataSource, "select count(*) from ingest.integration_source"));
        assertEquals(keys, Db.count(dataSource, "select count(*) from ingest.api_key"));
    }

    @Test
    void aProtectedEnvironmentGetsNoDemoSourcesAndNoKey() {
        new DemoKeyBootstrap(config("staging", "stagingport", null), jdbc, clock).run();

        assertEquals(
                0,
                Db.count(dataSource, "select count(*) from ingest.integration_source where port_id = 'stagingport'"));
    }

    @Test
    void withoutADemoKeyTheSourcesAreStillCreatedButNoKeyIsWritten() {
        new DemoKeyBootstrap(config("test", "keylessport", null), jdbc, clock).run();

        assertEquals(
                6,
                Db.count(dataSource, "select count(*) from ingest.integration_source where port_id = 'keylessport'"));
        assertEquals(
                0,
                Db.count(
                        dataSource,
                        "select count(*) from ingest.api_key k join ingest.integration_source s on s.id = k.source_id "
                                + "where s.port_id = 'keylessport'"));
        Db.execute(dataSource, "delete from ingest.integration_source where port_id = 'keylessport'");
    }

    @Test
    void theResetIsRefusedWhenItIsNotEnabled() {
        var protectedConfig = config("test", "westports", null);
        assertThrows(IllegalStateException.class, () -> new DataResetService(protectedConfig, null, null).reset());
    }

    @Test
    void readinessIncludesTheDatabaseMigrationsAndKafkaAndLivenessIsUp() {
        given().when()
                .get(ready)
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("Kafka"))
                .body("checks.name", hasItem("Database migrations"));
        given().when().get(live).then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void metricsCountTheIntakeOutcomesAndTheOpenApiDescribesTheEndpoint() {
        String id = Events.newSourceEventId();
        post(Events.status(id)).then().statusCode(202);
        post(Events.status(id)).then().statusCode(200);
        post(Events.EVENTS_PATH, "wrong", "{}").then().statusCode(401);

        String text =
                given().when().get(metrics).then().statusCode(200).extract().asString();

        for (String outcome : new String[] {"accepted", "duplicate", "rejected"}) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    text.matches(
                            "(?s).*castor_gateway_intake_total\\{[^}]*outcome=\"" + outcome + "\"[^}]*} [0-9.]+.*"),
                    outcome + " is not counted:\n"
                            + text.lines()
                                    .filter(l -> l.contains("castor_gateway"))
                                    .toList());
        }
        given().when()
                .get(openapi)
                .then()
                .statusCode(200)
                .body(containsString("/api/v1/ports/{portId}/integrations/{source}/events"));
    }
}
