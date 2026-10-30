package com.djavafactory.castor.gateway.support;

import java.sql.Timestamp;
import java.util.Random;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;

import com.djavafactory.castor.gateway.normalize.application.NormalizeInbox;
import com.djavafactory.castor.gateway.relay.application.RelayOutbox;
import com.djavafactory.castor.gateway.shared.domain.Uuid7;
import com.djavafactory.castor.gateway.shared.infrastructure.ConfiguredFailPoints;

import static io.restassured.RestAssured.given;

/** What every gateway integration test needs: empty pipeline tables, the test clock, the workers and a way to post. */
public abstract class PipelineSupport {

    @Inject
    protected DataSource dataSource;

    @Inject
    protected TestClock clock;

    @Inject
    protected NormalizeInbox normalizer;

    @Inject
    protected RelayOutbox relay;

    @Inject
    protected ConfiguredFailPoints failPoints;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    protected String bootstrapServers;

    @ConfigProperty(name = "castor.intake.demo-key")
    protected String demoKey;

    @BeforeEach
    void cleanPipeline() {
        Db.reset(dataSource);
        clock.reset();
        failPoints.disarm();
    }

    /** Posts to the canonical source of Westports with the demo key. */
    protected Response post(String body) {
        return post(Events.EVENTS_PATH, demoKey, body);
    }

    protected Response post(String path, String key, String body) {
        var request = given().contentType(ContentType.JSON).body(body);
        if (key != null) {
            request = request.header("X-Castor-Key", key);
        }
        return request.post(path);
    }

    /** Posts one valid event and returns its receptionId. */
    protected String accept(String sourceEventId) {
        return post(Events.status(sourceEventId))
                .then()
                .statusCode(202)
                .extract()
                .path("receptionId");
    }

    /** Puts a RECEIVED row straight into the inbox, for sources the endpoint does not serve yet. */
    protected UUID insertInbox(String source, String sourceEventId, String rawJson) {
        UUID id = Uuid7.generate(clock.instant(), new Random());
        Db.execute(
                dataSource,
                "insert into ingest.inbox (reception_id, port_id, source, source_event_id, received_at, raw, status) "
                        + "values (?, 'westports', ?, ?, ?, ?::jsonb, 'RECEIVED')",
                id,
                source,
                sourceEventId,
                Timestamp.from(clock.instant()),
                rawJson);
        return id;
    }

    protected String inboxStatus(String receptionId) {
        return Db.value(dataSource, "select status from ingest.inbox where reception_id = ?::uuid", receptionId);
    }
}
