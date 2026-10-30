package com.djavafactory.castor.gateway.relay.application;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.KafkaProbe;
import com.djavafactory.castor.gateway.support.KafkaProbe.Message;
import com.djavafactory.castor.gateway.support.PipelineSupport;
import com.djavafactory.castor.gateway.support.RestartableKafkaResource;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The broker goes away and comes back. Intake does not need it, so it keeps answering 202; the events wait in the outbox
 * and the relay publishes each of them once when the broker returns.
 */
@QuarkusTest
@TestProfile(RelayOutageIT.ShortSendTimeout.class)
@WithTestResource(value = RestartableKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class RelayOutageIT extends PipelineSupport {

    /** A broker that is away costs the relay this long per run; the default (10 s) would only slow the test. */
    public static class ShortSendTimeout implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("castor.relay.send-timeout", "PT3S");
        }
    }

    @TestHTTPResource(value = "/health/ready", management = true)
    URL ready;

    @TestHTTPResource(value = "/health/live", management = true)
    URL live;

    private long unpublished() {
        return Db.count(dataSource, "select count(*) from ingest.outbox where published_at is null");
    }

    @Test
    void intakeKeepsAnswering202WhileTheBrokerIsDownAndTheRelayCatchesUpWhenItIsBack() {
        // One event first, so the topics exist and the producer has been used before the outage
        String warmUp = Events.newSourceEventId();
        accept(warmUp);
        normalizer.runOnce();
        assertEquals(1, relay.runOnce());

        String run = java.util.UUID.randomUUID().toString();
        List<String> ids =
                IntStream.range(0, 5).mapToObj(i -> "outage-" + i + "-" + run).toList();

        RestartableKafkaResource.stopBroker();
        try {
            for (String id : ids) {
                post(Events.status(id)).then().statusCode(202);
            }
            assertEquals(5, normalizer.runOnce(), "normalizing needs only the database");

            assertEquals(0, relay.runOnce(), "nothing can be acknowledged");
            assertEquals(5, unpublished());

            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() ->
                            given().when().get(ready).then().statusCode(503).body("status", equalTo("DOWN")));
            // Not ready, but alive and still taking events
            given().when().get(live).then().statusCode(200).body("status", equalTo("UP"));
            post(Events.status("outage-late-" + run)).then().statusCode(202);
        } finally {
            RestartableKafkaResource.startBroker();
        }

        await().atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    normalizer.runOnce();
                    relay.runOnce();
                    return unpublished() == 0;
                });
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> given().when().get(ready).then().statusCode(200).body("checks.name", hasItem("Kafka")));

        List<String> expected = IntStream.rangeClosed(0, 5)
                .mapToObj(i -> i < 5 ? ids.get(i) : "outage-late-" + run)
                .toList();
        List<Message> messages = KafkaProbe.forSourceEvents(bootstrapServers, Set.copyOf(expected));
        // Delivery is at least once: a send that timed out while the broker was coming back may still have reached it,
        // and is then sent again. What must hold is that nothing is lost and that a repeat is the same event.
        assertEquals(
                Set.copyOf(expected),
                messages.stream().map(m -> m.event().sourceEventId()).collect(Collectors.toSet()),
                "every event reached Kafka, and nothing else did");
        assertEquals(6, messages.stream().map(Message::eventId).distinct().count(), "six events by eventId");
        Map<String, Set<CanonicalEvent>> copies = messages.stream()
                .collect(Collectors.groupingBy(
                        Message::eventId, Collectors.mapping(Message::event, Collectors.toSet())));
        assertTrue(copies.values().stream().allMatch(same -> same.size() == 1), "copies of an eventId are identical");
    }
}
