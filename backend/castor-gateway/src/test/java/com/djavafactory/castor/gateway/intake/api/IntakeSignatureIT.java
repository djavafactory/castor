package com.djavafactory.castor.gateway.intake.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.intake.domain.RequestSignature;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.PipelineSupport;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The signed webhook. The secret of source canonical is gateway.hmac.canonical (set for tests in application.properties),
 * the clock is the test clock, so every timestamp below is exact.
 */
@QuarkusTest
class IntakeSignatureIT extends PipelineSupport {

    private static final String SECRET = "test-hmac-secret";

    private String now() {
        return Long.toString(clock.instant().getEpochSecond());
    }

    private Response signed(String body, String timestamp, String signature) {
        var request = RestAssured.given()
                .contentType(ContentType.JSON)
                .header("X-Castor-Key", demoKey)
                .body(body.getBytes(StandardCharsets.UTF_8));
        if (timestamp != null) {
            request = request.header("X-Castor-Timestamp", timestamp);
        }
        if (signature != null) {
            request = request.header("X-Castor-Signature", signature);
        }
        return request.post(Events.EVENTS_PATH);
    }

    private Response signedProperly(String body, String timestamp) {
        return signed(body, timestamp, RequestSignature.sign(SECRET, timestamp, body.getBytes(StandardCharsets.UTF_8)));
    }

    private void require(boolean required, String secretRef) {
        Db.execute(
                dataSource,
                "update ingest.integration_source set require_signature = ?, hmac_secret_ref = ? "
                        + "where port_id = 'westports' and code = 'canonical'",
                required,
                secretRef);
    }

    @BeforeEach
    void requireSignatures() {
        require(true, "canonical");
    }

    @AfterEach
    void restoreSource() {
        require(false, null);
    }

    @Test
    void aSourceThatRequiresASignatureRefusesAnUnsignedRequest() {
        signed(Events.status(Events.newSourceEventId()), null, null)
                .then()
                .statusCode(401)
                .contentType("application/problem+json")
                .body("detail", containsString("requires"));
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.inbox"));
    }

    @Test
    void aValidSignatureIsAccepted() {
        signedProperly(Events.status(Events.newSourceEventId()), now()).then().statusCode(202);
    }

    @Test
    void anIsoTimestampWorksToo() {
        signedProperly(Events.status(Events.newSourceEventId()), clock.instant().toString())
                .then()
                .statusCode(202);
    }

    @Test
    void aWrongSignatureAnswers401() {
        signed(Events.status(Events.newSourceEventId()), now(), "00".repeat(32))
                .then()
                .statusCode(401)
                .body("detail", containsString("does not match"));
    }

    @Test
    void aSignatureMadeWithAnotherSecretAnswers401() {
        String body = Events.status(Events.newSourceEventId());
        String timestamp = now();

        signed(
                        body,
                        timestamp,
                        RequestSignature.sign("not-the-secret", timestamp, body.getBytes(StandardCharsets.UTF_8)))
                .then()
                .statusCode(401);
    }

    @Test
    void aBodyChangedAfterSigningAnswers401() {
        String timestamp = now();
        String signature = RequestSignature.sign(
                SECRET, timestamp, Events.status("original").getBytes(StandardCharsets.UTF_8));

        signed(Events.status("changed"), timestamp, signature).then().statusCode(401);
    }

    @Test
    void aTimestampChangedAfterSigningAnswers401() {
        String body = Events.status(Events.newSourceEventId());
        String signature = RequestSignature.sign(SECRET, now(), body.getBytes(StandardCharsets.UTF_8));

        signed(body, Long.toString(clock.instant().getEpochSecond() + 1), signature)
                .then()
                .statusCode(401);
    }

    @Test
    void aTimestampMoreThanFiveMinutesFromServerTimeAnswers401() {
        String body = Events.status(Events.newSourceEventId());

        for (Duration offset :
                new Duration[] {Duration.ofSeconds(-301), Duration.ofSeconds(301), Duration.ofHours(-1)}) {
            signedProperly(body, Long.toString(clock.instant().plus(offset).getEpochSecond()))
                    .then()
                    .statusCode(401)
                    .body("detail", containsString("5 minutes"));
        }
        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.inbox"));
    }

    @Test
    void aTimestampWithinFiveMinutesIsAcceptedEvenAsTheClockMoves() {
        String old = Events.status(Events.newSourceEventId());
        String oldTimestamp = now();

        clock.advance(Duration.ofMinutes(4));
        signedProperly(old, oldTimestamp).then().statusCode(202);

        // Replaying the same signed request 6 minutes after it was made is refused
        clock.advance(Duration.ofMinutes(2));
        signedProperly(Events.status(Events.newSourceEventId()), oldTimestamp)
                .then()
                .statusCode(401);
    }

    @Test
    void halfASignatureAnswers401() {
        String body = Events.status(Events.newSourceEventId());

        signed(body, now(), null).then().statusCode(401).body("detail", containsString("together"));
        signed(body, null, "ab".repeat(32)).then().statusCode(401);
    }

    @Test
    void aRequiredSignatureCannotBeCheckedWithoutAConfiguredSecret() {
        // The reference is a valid name, but gateway.hmac.<name> is not configured
        require(true, "no-such-secret");

        signedProperly(Events.status(Events.newSourceEventId()), now()).then().statusCode(401);
    }

    @Test
    void theKeyIsStillCheckedFirst() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .header("X-Castor-Key", "wrong")
                .body("{}")
                .post(Events.EVENTS_PATH)
                .then()
                .statusCode(401)
                .body("detail", containsString("API key"));
    }

    @Test
    void whenNoSignatureIsRequiredAnUnsignedRequestStillWorksAndASignedOneIsChecked() {
        require(false, "canonical");

        signed(Events.status(Events.newSourceEventId()), null, null).then().statusCode(202);
        signedProperly(Events.status(Events.newSourceEventId()), now()).then().statusCode(202);
        signed(Events.status(Events.newSourceEventId()), now(), "00".repeat(32))
                .then()
                .statusCode(401);
    }
}
