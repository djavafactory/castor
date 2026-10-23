package it.replica8.castor.core.shared.infrastructure;

import java.net.URL;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class MetricsIT {

    @TestHTTPResource(value = "/metrics", management = true)
    URL metrics;

    @Test
    void prometheusEndpointShowsHttpDatabaseAndCastorMetrics() {
        given().when().get("/api/v1/layouts").then().statusCode(200);
        given().when().get("/test-problems/not-found").then().statusCode(404);

        given().when()
                .get(metrics)
                .then()
                .statusCode(200)
                .body(containsString("http_server_requests_seconds_count"))
                .body(containsString("uri=\"/api/v1/layouts\""))
                .body(containsString("status=\"404\""))
                .body(containsString("agroal_"))
                .body(containsString("castor_validation_duration_seconds"))
                .body(containsString("castor_publish_count_total"))
                .body(containsString("castor_import_count_total"))
                .body(containsString("castor_spatial_query_duration_seconds"));
    }
}
