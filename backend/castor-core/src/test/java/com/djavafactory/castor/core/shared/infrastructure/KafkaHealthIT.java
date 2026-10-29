package com.djavafactory.castor.core.shared.infrastructure;

import java.net.URL;
import java.time.Duration;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.support.RestartableKafkaResource;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** Readiness follows the broker: DOWN while it is stopped, UP again when it returns. Live never depends on Kafka. */
@QuarkusTest
@WithTestResource(value = RestartableKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class KafkaHealthIT {

    @TestHTTPResource(value = "/health/ready", management = true)
    URL ready;

    @TestHTTPResource(value = "/health/live", management = true)
    URL live;

    @Test
    void readyFollowsTheBroker() {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> given().when()
                        .get(ready)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("UP"))
                        .body("checks.name", hasItem("Kafka")));

        RestartableKafkaResource.stopBroker();
        try {
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() ->
                            given().when().get(ready).then().statusCode(503).body("status", equalTo("DOWN")));
            given().when()
                    .get(live)
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"))
                    .body("checks.name", not(hasItem("Kafka")));
        } finally {
            RestartableKafkaResource.startBroker();
        }

        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> given().when().get(ready).then().statusCode(200).body("status", equalTo("UP")));
    }
}
