package it.replica8.castor.core.shared.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import it.replica8.castor.core.support.LocalProfile;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(LocalProfile.class)
class OpenApiLocalIT {

    @Test
    void openApiIsOpenInLocal() {
        given().header("Accept", "application/json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("Castor Core API"));
    }

    @Test
    void swaggerUiIsOpenInLocal() {
        given().when().get("/q/swagger-ui/").then().statusCode(200).body(containsString("swagger"));
    }
}
