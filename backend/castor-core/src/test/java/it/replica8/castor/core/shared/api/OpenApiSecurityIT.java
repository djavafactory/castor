package it.replica8.castor.core.shared.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** The test profile behaves like an environment outside local and dev. */
@QuarkusTest
class OpenApiSecurityIT {

    @Test
    void openApiNeedsLogin() {
        given().when().get("/q/openapi").then().statusCode(401);
    }

    @Test
    void swaggerUiNeedsLogin() {
        given().redirects().follow(false).when().get("/q/swagger-ui/").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void nonAdminIsForbidden() {
        given().when().get("/q/openapi").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "boss", roles = "ADMIN")
    void adminSeesTitleAndAppVersion() throws IOException {
        String version = Files.readString(Path.of("..", "..", "VERSION")).trim();

        given().header("Accept", "application/json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("Castor Core API"))
                .body("info.version", equalTo(version));
    }

    @Test
    @TestSecurity(user = "boss", roles = "ADMIN")
    void adminSeesSwaggerUi() {
        given().when().get("/q/swagger-ui/").then().statusCode(200).body(containsString("swagger"));
    }
}
