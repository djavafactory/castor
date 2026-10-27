package com.djavafactory.castor.core.shared.api;

import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class VersionIT {

    @Test
    void answersWithoutLoginAndCarriesTheBuildStamps() throws Exception {
        String release = Files.readString(Path.of("..", "..", "VERSION")).trim();

        given().when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("version", startsWith(release))
                .body("commit", matchesPattern("[0-9a-f]{7}|unknown"))
                .body("buildTime", matchesPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))
                .body("environment", equalTo("local"));
    }
}
