package com.djavafactory.castor.core.shared.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ProblemDetailsIT {

    private static final String PROBLEM = "application/problem+json";
    private static final String BASE = "https://replica8.it/problems/";

    @Test
    void beanValidationGives400() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"\"}")
                .when()
                .post("/test-problems/validation")
                .then()
                .statusCode(400)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "bad-request"))
                .body("title", equalTo("Invalid request"))
                .body("status", equalTo(400))
                .body("detail", equalTo("1 constraint failed"))
                .body("instance", equalTo("/test-problems/validation"))
                .body("requestId", notNullValue())
                .body("errors", hasSize(1))
                .body("errors[0].code", equalTo("NOTBLANK"))
                .body("errors[0].pointer", equalTo("/name"));
    }

    @Test
    void malformedJsonGives400() {
        given().contentType(ContentType.JSON)
                .body("{not json")
                .when()
                .post("/test-problems/validation")
                .then()
                .statusCode(400)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "bad-request"))
                .body("status", equalTo(400));
    }

    @Test
    void notFoundGives404() {
        given().when()
                .get("/test-problems/not-found")
                .then()
                .statusCode(404)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "not-found"))
                .body("title", equalTo("Not found"))
                .body("status", equalTo(404))
                .body("detail", equalTo("Layout 9c1 does not exist"))
                .body("instance", equalTo("/test-problems/not-found"));
    }

    @Test
    void unknownPathGives404Problem() {
        given().when()
                .get("/no/such/path")
                .then()
                .statusCode(404)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "not-found"));
    }

    @Test
    void draftExistsGives409() {
        given().when()
                .get("/test-problems/draft-exists")
                .then()
                .statusCode(409)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "conflict"))
                .body("title", equalTo("Conflict"))
                .body("status", equalTo(409))
                .body("detail", equalTo("A draft already exists for this layout"));
    }

    @Test
    void optimisticLockGives409WithoutInternalDetail() {
        given().when()
                .get("/test-problems/optimistic-lock")
                .then()
                .statusCode(409)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "conflict"))
                .body("status", equalTo(409))
                .body("detail", not(containsString("hibernate")))
                .body("detail", not(containsString("layout.asset")));
    }

    @Test
    void missingIfMatchGives412() {
        given().when()
                .get("/test-problems/missing-if-match")
                .then()
                .statusCode(412)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "precondition-failed"))
                .body("title", equalTo("Precondition failed"))
                .body("status", equalTo(412))
                .body("detail", equalTo("The If-Match header is required"));
    }

    @Test
    void unsupportedMediaTypeGives415() {
        given().contentType("text/plain")
                .body("hello")
                .when()
                .post("/test-problems/echo")
                .then()
                .statusCode(415)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "unsupported-media-type"))
                .body("title", equalTo("Unsupported media type"))
                .body("status", equalTo(415));
    }

    @Test
    void domainRuleViolationGives422() {
        given().when()
                .get("/test-problems/rule")
                .then()
                .statusCode(422)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "validation"))
                .body("title", equalTo("Invalid asset"))
                .body("status", equalTo(422))
                .body("detail", equalTo("1 rule failed"))
                .body("instance", equalTo("/test-problems/rule"))
                .body("errors", hasSize(1))
                .body("errors[0].code", equalTo("OVERLAP"))
                .body("errors[0].assetId", equalTo("5f0c1b1e-0000-4000-8000-000000000001"))
                .body("errors[0].pointer", equalTo("/geometry"))
                .body("errors[0].message", equalTo("Overlaps Warehouse A"));
    }

    @Test
    void anythingElseGives500WithOnlyTheAgreedFields() throws Exception {
        String body = given().when()
                .get("/test-problems/boom")
                .then()
                .statusCode(500)
                .contentType(PROBLEM)
                .body("type", equalTo(BASE + "internal"))
                .body("title", equalTo("Unexpected error"))
                .body("status", equalTo(500))
                .body("errorId", matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .body("detail", nullValue())
                .body("instance", nullValue())
                .body("errors", nullValue())
                .extract()
                .asString();

        org.junit.jupiter.api.Assertions.assertEquals(
                4,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).size(),
                body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("select"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Secret"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("IllegalStateException"), body);
    }
}
