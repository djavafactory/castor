package com.djavafactory.castor.core.shared.infrastructure;

import java.net.URL;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.support.NoDatabaseProfile;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(NoDatabaseProfile.class)
class HealthWithoutDatabaseIT {

    @TestHTTPResource(value = "/health/ready", management = true)
    URL ready;

    @TestHTTPResource(value = "/health/live", management = true)
    URL live;

    @Test
    void readyIsDownWithoutDatabase() {
        given().when().get(ready).then().statusCode(503).body("status", equalTo("DOWN"));
    }

    @Test
    void liveStaysUpWithoutDatabase() {
        given().when().get(live).then().statusCode(200).body("status", equalTo("UP"));
    }
}
