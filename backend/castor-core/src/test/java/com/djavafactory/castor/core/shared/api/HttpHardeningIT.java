package com.djavafactory.castor.core.shared.api;

import java.io.ByteArrayInputStream;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class HttpHardeningIT {

    private static final String ALLOWED = "http://localhost:5173";

    private static ValidatableResponse hardened(ValidatableResponse response) {
        return response.header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Referrer-Policy", equalTo("no-referrer"));
    }

    @Test
    void normalResponseCarriesSecurityHeaders() {
        hardened(given().when().get("/api/v1/layouts").then().statusCode(200))
                .header("Strict-Transport-Security", nullValue());
    }

    @Test
    void errorResponseCarriesSecurityHeaders() {
        hardened(given().when().get("/test-problems/boom").then().statusCode(500));
        hardened(given().when().get("/no/such/path").then().statusCode(404));
    }

    @Test
    void strictTransportSecurityIsAddedBehindHttps() {
        given().header("X-Forwarded-Proto", "https")
                .when()
                .get("/api/v1/layouts")
                .then()
                .header("Strict-Transport-Security", containsString("max-age="));
    }

    @Test
    void preflightFromAllowedOriginSucceeds() {
        hardened(given().header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "If-Match, Content-Type, Authorization")
                        .when()
                        .options("/api/v1/layouts")
                        .then()
                        .statusCode(200))
                .header("Access-Control-Allow-Origin", equalTo(ALLOWED))
                .header("Access-Control-Allow-Methods", containsString("PATCH"))
                .header("Access-Control-Allow-Headers", containsString("If-Match"))
                .header("Access-Control-Allow-Credentials", not(equalTo("true")));
    }

    @Test
    void preflightFromUnknownOriginIsRefused() {
        given().header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")
                .when()
                .options("/api/v1/layouts")
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void actualResponseExposesETagAndLocation() {
        given().header("Origin", ALLOWED)
                .when()
                .get("/api/v1/layouts")
                .then()
                .header("Access-Control-Allow-Origin", equalTo(ALLOWED))
                .header("Access-Control-Expose-Headers", containsString("ETag"))
                .header("Access-Control-Expose-Headers", containsString("Location"));
    }

    @Test
    void jsonAndGeoJsonAreAcceptedOnWrites() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .post("/test-problems/echo")
                .then()
                .statusCode(200)
                .body(equalTo("2"));
        given().contentType("application/geo+json")
                .body("{}")
                .post("/test-problems/echo")
                .then()
                .statusCode(200)
                .body(equalTo("2"));
    }

    @Test
    void bodyOverOneMegabyteIsRefused() {
        hardened(given().contentType(ContentType.JSON)
                        .body("\"" + "a".repeat(1024 * 1024) + "\"")
                        .post("/test-problems/echo")
                        .then()
                        .statusCode(413))
                .contentType("application/problem+json")
                .body("type", equalTo("https://replica8.it/problems/payload-too-large"));
    }

    @Test
    void chunkedBodyOverOneMegabyteIsRefused() {
        byte[] big = ("\"" + "a".repeat(1024 * 1024 + 10) + "\"").getBytes();

        given().contentType(ContentType.JSON)
                .body(new ByteArrayInputStream(big))
                .post("/test-problems/echo")
                .then()
                .statusCode(413)
                .body("status", equalTo(413));
    }

    @Test
    @TestSecurity(user = "editor", roles = "EDITOR")
    void importEndpointAcceptsUpToTenMegabytes() {
        // past the size guard the file is read: it is not JSON, which the endpoint answers with its own 400
        given().multiPart("file", "big.geojson", "a".repeat(5 * 1024 * 1024).getBytes(), "application/geo+json")
                .post("/api/v1/layouts/9c1a0000-0000-4000-8000-000000000001/import")
                .then()
                .statusCode(400)
                .body("detail", containsString("not valid JSON"));
    }

    @Test
    @TestSecurity(user = "editor", roles = "EDITOR")
    void importEndpointRefusesMoreThanTenMegabytes() {
        // The server may answer 413 or cut the connection while the client is still sending; both mean refused
        try {
            given().multiPart(
                            "file",
                            "big.geojson",
                            "a".repeat(10 * 1024 * 1024 + 1).getBytes(),
                            "application/geo+json")
                    .post("/api/v1/layouts/9c1a0000-0000-4000-8000-000000000001/import")
                    .then()
                    .statusCode(413);
        } catch (Exception connectionClosed) {
            // refused
        }
    }

    @Test
    void requestIdIsReturnedAndReusedWhenSafe() {
        given().when().get("/api/v1/layouts").then().header("X-Request-Id", notNullValue());
        given().header("X-Request-Id", "client-request-0001")
                .when()
                .get("/api/v1/layouts")
                .then()
                .header("X-Request-Id", equalTo("client-request-0001"));
        given().header("X-Request-Id", "bad id\twith spaces")
                .when()
                .get("/api/v1/layouts")
                .then()
                .header("X-Request-Id", not(containsString(" ")));
    }

    @Test
    void layoutListIsEmptyJson() {
        given().when()
                .get("/api/v1/layouts")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", equalTo(0));
    }
}
