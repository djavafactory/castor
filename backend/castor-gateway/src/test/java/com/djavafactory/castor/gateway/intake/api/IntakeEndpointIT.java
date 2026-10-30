package com.djavafactory.castor.gateway.intake.api;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.intake.application.IssueApiKey;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.PipelineSupport;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The intake endpoint: authentication, the order of the checks, durable acceptance and duplicates. */
@QuarkusTest
class IntakeEndpointIT extends PipelineSupport {

    @Inject
    IssueApiKey issueApiKey;

    @TestHTTPResource(Events.EVENTS_PATH)
    URL eventsUrl;

    @AfterEach
    void removeIssuedKeys() {
        Db.execute(dataSource, "delete from ingest.api_key where origin = 'issued'");
    }

    /** A record of exactly {@code size} bytes that passes intake. */
    private static String recordOfSize(int size) {
        String head = "{\"sourceEventId\":\"" + Events.newSourceEventId()
                + "\",\"occurredAt\":\"2026-10-29T08:20:00Z\",\"pad\":\"";
        String tail = "\"}";
        return head + "x".repeat(size - head.length() - tail.length()) + tail;
    }

    private long inboxRows() {
        return Db.count(dataSource, "select count(*) from ingest.inbox");
    }

    // --- accepted ---

    @Test
    void aNewRecordAnswers202WithAReceptionIdAndIsInTheDatabaseAlready() {
        String id = Events.newSourceEventId();

        Response response = post(Events.status(id));

        response.then()
                .statusCode(202)
                .contentType("application/json")
                .body("receptionId", matchesPattern("[0-9a-f-]{36}"))
                .body("duplicate", nullValue());
        // The answer was sent after the commit: another connection sees the row
        UUID receptionId = UUID.fromString(response.path("receptionId"));
        assertEquals(7, receptionId.version(), "reception ids are UUIDv7");
        assertEquals("RECEIVED", inboxStatus(receptionId.toString()));
        assertEquals(
                "westports", Db.value(dataSource, "select port_id from ingest.inbox where source_event_id = ?", id));
        assertEquals(
                "canonical", Db.value(dataSource, "select source from ingest.inbox where source_event_id = ?", id));
        assertEquals(
                java.sql.Timestamp.from(clock.instant()),
                Db.value(dataSource, "select received_at from ingest.inbox where source_event_id = ?", id),
                "received_at comes from the injected clock");
        assertEquals(
                "BLOCKED",
                Db.value(
                        dataSource,
                        "select raw -> 'payload' ->> 'status' from ingest.inbox where source_event_id = ?",
                        id));
    }

    @Test
    void theSameSourceEventIdTwiceStoresOneRowAndAnswersDuplicate() {
        String id = Events.newSourceEventId();
        String first = post(Events.status(id)).then().statusCode(202).extract().path("receptionId");

        post(Events.status(id))
                .then()
                .statusCode(200)
                .body("receptionId", equalTo(first))
                .body("duplicate", is(true));
        post(Events.status(id))
                .then()
                .statusCode(200)
                .body("receptionId", equalTo(first))
                .body("duplicate", is(true));

        assertEquals(1, Db.count(dataSource, "select count(*) from ingest.inbox where source_event_id = ?", id));
    }

    @Test
    void aRepeatIsRecognisedWhateverElseChanged() {
        String id = Events.newSourceEventId();
        String first = post(Events.status(id)).then().statusCode(202).extract().path("receptionId");

        post(Events.status(id, "other-aggregate", "2026-10-29T09:00:00Z"))
                .then()
                .statusCode(200)
                .body("receptionId", equalTo(first));
    }

    @Test
    void theSameSourceEventIdOnAnotherSourceIsNotARepeat() {
        String id = Events.newSourceEventId();
        accept(id);
        // cbas is disabled for the demo key, so give canonical's neighbour a row by hand to prove the key is per source
        Db.execute(
                dataSource,
                "insert into ingest.inbox (reception_id, port_id, source, source_event_id, received_at, raw) "
                        + "values (?, 'westports', 'cbas', ?, now(), '{}')",
                UUID.randomUUID(),
                id);

        assertEquals(2, Db.count(dataSource, "select count(*) from ingest.inbox where source_event_id = ?", id));
    }

    @Test
    void concurrentRepeatsStoreOneRowAndAgreeOnTheReceptionId() throws Exception {
        String id = Events.newSourceEventId();
        String body = Events.status(id);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Response>> calls = IntStream.range(0, 8)
                    .<Callable<Response>>mapToObj(i -> () -> post(body))
                    .toList();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : pool.invokeAll(calls)) {
                responses.add(future.get());
            }

            assertEquals(
                    1, responses.stream().filter(r -> r.statusCode() == 202).count());
            assertEquals(
                    7, responses.stream().filter(r -> r.statusCode() == 200).count());
            assertEquals(
                    1,
                    responses.stream()
                            .map(r -> r.<String>path("receptionId"))
                            .collect(Collectors.toSet())
                            .size());
            assertEquals(1, Db.count(dataSource, "select count(*) from ingest.inbox where source_event_id = ?", id));
        } finally {
            pool.shutdownNow();
        }
    }

    // --- 401 ---

    @Test
    void aMissingOrWrongKeyAnswers401AsProblemDetails() {
        for (String key : new String[] {null, "", "  ", "wrong-key", demoKey + "x", demoKey.toUpperCase() + "!"}) {
            Response response = post(Events.EVENTS_PATH, key, Events.status(Events.newSourceEventId()));

            response.then()
                    .statusCode(401)
                    .contentType("application/problem+json")
                    .header("WWW-Authenticate", "X-Castor-Key")
                    .body("status", equalTo(401))
                    .body("type", equalTo("https://replica8.it/problems/unauthorized"))
                    .body("instance", equalTo(Events.EVENTS_PATH))
                    .body("requestId", notNullValue());
        }
        assertEquals(0, inboxRows());
    }

    @Test
    void theProblemCarriesTheRequestIdOfTheResponseHeader() {
        Response response = post(Events.EVENTS_PATH, "wrong-key", "{}");

        assertEquals(response.header("X-Request-Id"), response.path("requestId"));
    }

    @Test
    void anUnknownPortOrSourceAnswers401BecauseNoKeyExistsForIt() {
        String id = Events.status(Events.newSourceEventId());

        post("/api/v1/ports/nowhere/integrations/canonical/events", demoKey, id)
                .then()
                .statusCode(401);
        post("/api/v1/ports/westports/integrations/telepathy/events", demoKey, id)
                .then()
                .statusCode(401);
        post("/api/v1/ports/westports/integrations/CANONICAL/events", demoKey, id)
                .then()
                .statusCode(401);
    }

    @Test
    void aKeyOfAnotherSourceAnswers401() {
        String key = issueApiKey.issue("westports", Source.CBAS).key();

        post(Events.EVENTS_PATH, key, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(401);
    }

    @Test
    void aRevokedKeyAnswers401() {
        String key = issueApiKey.issue("westports", Source.CANONICAL).key();
        post(Events.EVENTS_PATH, key, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(202);

        Db.execute(dataSource, "update ingest.api_key set revoked_at = now() where origin = 'issued'");

        post(Events.EVENTS_PATH, key, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(401);
        // The demo key is a different key and still works
        post(Events.status(Events.newSourceEventId())).then().statusCode(202);
    }

    @Test
    void anIssuedKeyIsStoredOnlyAsAHash() {
        var issued = issueApiKey.issue("westports", Source.CANONICAL);

        assertTrue(issued.key().startsWith("ck_") && issued.key().length() >= 46, issued.key());
        assertEquals(issued.key().substring(0, 8), issued.prefix());
        assertEquals(
                1,
                Db.count(
                        dataSource,
                        "select count(*) from ingest.api_key where prefix = ? and origin = 'issued'",
                        issued.prefix()));
        assertEquals(32, (int) Db.value(
                dataSource, "select octet_length(key_hash) from ingest.api_key where prefix = ?", issued.prefix()));
        assertEquals(
                0,
                Db.count(
                        dataSource,
                        "select count(*) from ingest.api_key where convert_from(key_hash, 'LATIN1') like ? or prefix = ?",
                        "%" + issued.key() + "%",
                        issued.key()));
        post(Events.EVENTS_PATH, issued.key(), Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(202);
    }

    // --- 403 ---

    @Test
    void aDisabledSourceAnswers403() {
        post("/api/v1/ports/westports/integrations/cbas/events", demoKey, Events.status(Events.newSourceEventId()))
                .then()
                .statusCode(403)
                .contentType("application/problem+json")
                .body("title", equalTo("Source disabled"))
                .body("detail", containsString("cbas"));
    }

    // --- 413, 400, 422 ---

    @Test
    void aBodyOfExactly256KbIsAcceptedAndOneByteMoreAnswers413() {
        post(recordOfSize(262_144)).then().statusCode(202);

        post(recordOfSize(262_145))
                .then()
                .statusCode(413)
                .contentType("application/problem+json")
                .body("status", equalTo(413))
                .body("detail", containsString("256 KB"));
    }

    @Test
    void aBodyWithoutContentLengthIsStillStoppedAt256Kb() throws Exception {
        // java.net.http sends a stream of unknown length chunked, so only the byte count can stop it
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(eventsUrl.toString()))
                                .header("X-Castor-Key", demoKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(
                                        recordOfSize(300_000).getBytes(StandardCharsets.UTF_8))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode());
        assertEquals(0, inboxRows());
    }

    @Test
    void malformedJsonAnswers400() {
        for (String body : new String[] {"{\"sourceEventId\":", "not json", "", "{} {}", "[1,", "'single'"}) {
            post(body)
                    .then()
                    .statusCode(400)
                    .contentType("application/problem+json")
                    .body("status", equalTo(400));
        }
        assertEquals(0, inboxRows());
    }

    @Test
    void jsonThatIsNeitherAnObjectNorAnArrayAnswers400() {
        for (String body : new String[] {"42", "\"text\"", "null", "true"}) {
            post(body).then().statusCode(400).body("detail", containsString("object or an array"));
        }
    }

    @Test
    void aRecordWithoutSourceEventIdOrOccurredAtAnswers422WithThePointers() {
        post("{\"type\":\"x\"}")
                .then()
                .statusCode(422)
                .contentType("application/problem+json")
                .body("errors", hasSize(2))
                .body("errors[0].code", equalTo("REQUIRED"))
                .body("errors[0].pointer", equalTo("/sourceEventId"))
                .body("errors[1].pointer", equalTo("/occurredAt"));
        post("{\"sourceEventId\":\"a\"}")
                .then()
                .statusCode(422)
                .body("errors", hasSize(1))
                .body("errors[0].pointer", equalTo("/occurredAt"));
        post("{\"sourceEventId\":\"a\",\"occurredAt\":\"yesterday\"}")
                .then()
                .statusCode(422)
                .body("errors[0].code", equalTo("INVALID_FORMAT"));
        assertEquals(0, inboxRows());
    }

    @Test
    void aStoredRecordKeepsItsExactNumbers() {
        String id = Events.newSourceEventId();
        post("{\"sourceEventId\":\"" + id
                        + "\",\"occurredAt\":\"2026-10-29T08:20:00Z\",\"value\":1.50,\"big\":12345678901234567890.123456789}")
                .then()
                .statusCode(202);

        assertEquals(
                "1.50",
                Db.value(dataSource, "select (raw -> 'value')::text from ingest.inbox where source_event_id = ?", id));
        assertEquals(
                "12345678901234567890.123456789",
                Db.value(dataSource, "select (raw -> 'big')::text from ingest.inbox where source_event_id = ?", id));
    }

    @Test
    void jsonTheDatabaseCannotHoldAnswers400() {
        post("{\"sourceEventId\":\"a\",\"occurredAt\":\"2026-10-29T08:20:00Z\",\"x\":\"\\u0000\"}")
                .then()
                .statusCode(400)
                .body("detail", containsString("cannot store"));
        assertEquals(0, inboxRows());
    }

    // --- order of the checks ---

    @Test
    void theChecksRunInTheDocumentedOrder() {
        String oversize = "{\"sourceEventId\":" + "\"" + "x".repeat(300_000) + "\"";
        String malformed = "{\"sourceEventId\":";
        String disabledPath = "/api/v1/ports/westports/integrations/cbas/events";

        // key (401) before source enabled (403) before size (413) before JSON (400) before fields (422)
        post(disabledPath, "wrong-key", malformed).then().statusCode(401);
        post(Events.EVENTS_PATH, "wrong-key", oversize).then().statusCode(401);
        post(disabledPath, demoKey, oversize).then().statusCode(403);
        post(disabledPath, demoKey, malformed).then().statusCode(403);
        post(Events.EVENTS_PATH, demoKey, oversize).then().statusCode(413);
        post(Events.EVENTS_PATH, demoKey, malformed).then().statusCode(400);
        post(Events.EVENTS_PATH, demoKey, "{}").then().statusCode(422);
    }

    // --- arrays ---

    @Test
    void anArrayIsAnsweredPerItem() {
        String a = Events.newSourceEventId();
        String b = Events.newSourceEventId();
        String body = "[" + Events.status(a) + ",{\"sourceEventId\":\"no-time\"}," + Events.status(a) + ","
                + Events.status(b) + ",\"not an object\"]";

        post(body)
                .then()
                .statusCode(207)
                .contentType("application/json")
                .body("items", hasSize(5))
                .body("items[0].index", equalTo(0))
                .body("items[0].status", equalTo(202))
                .body("items[1].status", equalTo(422))
                .body("items[1].errors[0].pointer", equalTo("/occurredAt"))
                .body("items[1].receptionId", nullValue())
                .body("items[2].status", equalTo(200))
                .body("items[2].duplicate", is(true))
                .body("items[3].status", equalTo(202))
                .body("items[4].status", equalTo(422))
                .body("items[4].errors[0].code", equalTo("NOT_AN_OBJECT"));

        // The repeat inside the request names the first record's receptionId
        Response again = post("[" + Events.status(a) + "]");
        again.then().statusCode(207).body("items[0].status", equalTo(200));
        assertEquals(2, inboxRows());
    }

    @Test
    void theItemsOfAnArrayKeepTheirOrderInTheInbox() {
        String body = IntStream.range(0, 50)
                .mapToObj(i -> Events.status("order-" + i + "-" + UUID.randomUUID()))
                .collect(Collectors.joining(",", "[", "]"));

        post(body).then().statusCode(207);

        List<String> bySequence =
                Db.column(dataSource, "select source_event_id from ingest.inbox order by reception_id");
        for (int i = 0; i < 50; i++) {
            assertTrue(bySequence.get(i).startsWith("order-" + i + "-"), "position " + i + " is " + bySequence.get(i));
        }
    }

    @Test
    void anArrayOf500IsAcceptedAnd501Answers413() {
        String ok = IntStream.range(0, 500)
                .mapToObj(i -> "{\"sourceEventId\":\"b500-" + i + "\",\"occurredAt\":\"2026-10-29T08:20:00Z\"}")
                .collect(Collectors.joining(",", "[", "]"));
        String tooMany = IntStream.range(0, 501)
                .mapToObj(i -> "{\"sourceEventId\":\"b501-" + i + "\",\"occurredAt\":\"2026-10-29T08:20:00Z\"}")
                .collect(Collectors.joining(",", "[", "]"));

        post(ok).then().statusCode(207).body("items.status", everyItem(is(202)));
        post(tooMany).then().statusCode(413).body("detail", containsString("500"));

        assertEquals(500, inboxRows());
    }

    @Test
    void anEmptyArrayAnswers422() {
        post("[]").then().statusCode(422).body("errors[0].code", equalTo("EMPTY"));
    }

    @Test
    void aRecordTheDatabaseCannotHoldDoesNotSpoilTheRestOfAnArray() {
        String good = Events.newSourceEventId();
        String body = "[{\"sourceEventId\":\"nul\",\"occurredAt\":\"2026-10-29T08:20:00Z\",\"x\":\"\\u0000\"},"
                + Events.status(good) + "]";

        post(body)
                .then()
                .statusCode(207)
                .body("items[0].status", equalTo(400))
                .body("items[0].errors[0].code", equalTo("UNSTORABLE"))
                .body("items[1].status", equalTo(202));
        assertEquals(1, inboxRows());
    }

    @Test
    void everyAnswerHasTheSecurityHeaders() {
        post(Events.EVENTS_PATH, "wrong", "{}")
                .then()
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Cache-Control", "no-store");
    }

    @Test
    void otherPathsAndMethodsAnswerProblemDetails() {
        io.restassured.RestAssured.get(Events.EVENTS_PATH)
                .then()
                .statusCode(405)
                .contentType("application/problem+json");
        io.restassured.RestAssured.get("/api/v1/nothing")
                .then()
                .statusCode(404)
                .contentType("application/problem+json");
    }
}
