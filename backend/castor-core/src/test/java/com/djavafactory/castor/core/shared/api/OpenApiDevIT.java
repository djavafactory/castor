package com.djavafactory.castor.core.shared.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class OpenApiDevIT {

    @Test
    void openApiIsOpenInDev() {
        given().header("Accept", "application/json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("Castor Core API"));
    }

    @Test
    void swaggerUiIsOpenInDev() {
        given().when().get("/q/swagger-ui/").then().statusCode(200).body(containsString("swagger"));
    }
}
