package it.replica8.castor.core.shared.infrastructure;

import java.net.URL;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class HealthIT {

    @TestHTTPResource(value = "/health/ready", management = true)
    URL ready;

    @TestHTTPResource(value = "/health/live", management = true)
    URL live;

    @Test
    void readyIsUpWhenDatabaseAndMigrationsAreDone() {
        given().when()
                .get(ready)
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("Database migrations"));
    }

    @Test
    void liveChecksOnlyTheJvm() {
        given().when()
                .get(live)
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", not(hasItem("Database migrations")));
    }

    @Test
    void healthIsNotServedOnTheApiPort() {
        given().when().get("/q/health/ready").then().statusCode(404).body(not(containsString("UP")));
    }
}
