package com.djavafactory.castor.gateway.intake.application;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.KafkaProbe;
import com.djavafactory.castor.gateway.support.KafkaProbe.Message;
import com.djavafactory.castor.gateway.support.LivePipeline;
import com.djavafactory.castor.gateway.support.PipelineSupport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Intake to Kafka with the scheduled workers doing the work, nothing run by hand. */
@QuarkusTest
@TestProfile(LivePipeline.class)
class IntakePipelineIT extends PipelineSupport {

    private long unpublished() {
        return Db.count(dataSource, "select count(*) from ingest.outbox where published_at is null");
    }

    private long pending() {
        return Db.count(dataSource, "select count(*) from ingest.inbox where status = 'RECEIVED'");
    }

    @Test
    void acceptedEventsReachKafkaOnceEachAndARepeatDoesNotPublishAgain() {
        String run = UUID.randomUUID().toString();
        List<String> ids =
                IntStream.range(0, 3).mapToObj(i -> "live-" + i + "-" + run).toList();

        for (String id : ids) {
            post(Events.status(id)).then().statusCode(202);
        }
        post(Events.status(ids.get(0))).then().statusCode(200);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> pending() == 0
                        && unpublished() == 0
                        && Db.count(dataSource, "select count(*) from ingest.outbox") == 3);
        List<Message> messages = KafkaProbe.forSourceEvents(bootstrapServers, Set.copyOf(ids));
        assertEquals(3, messages.size(), "three accepted events, three messages: the repeat added none");
        assertEquals(
                Set.copyOf(ids),
                messages.stream().map(m -> m.event().sourceEventId()).collect(Collectors.toSet()));
        assertEquals(3, Db.count(dataSource, "select count(*) from ingest.inbox"));
    }

    @Test
    void anInvalidEnvelopeIsQuarantinedByTheWorkersAndNeverPublished() {
        String run = UUID.randomUUID().toString();
        String bad = "bad-" + run;
        String good = "good-" + run;
        post(Events.invalidStatus(bad)).then().statusCode(202);
        post(Events.status(good)).then().statusCode(202);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> pending() == 0 && unpublished() == 0);

        assertEquals(
                1, KafkaProbe.forSourceEvents(bootstrapServers, Set.of(good)).size());
        assertTrue(KafkaProbe.forSourceEvents(bootstrapServers, Set.of(bad)).isEmpty());
        assertEquals(
                "SCHEMA_INVALID",
                Db.value(
                        dataSource,
                        "select q.reason_code from ingest.quarantine q join ingest.inbox i using (reception_id) "
                                + "where i.source_event_id = ?",
                        bad));
    }
}
