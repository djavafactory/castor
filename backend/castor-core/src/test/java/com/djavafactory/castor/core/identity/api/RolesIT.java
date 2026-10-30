package com.djavafactory.castor.core.identity.api;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Role OPERATOR (STATE-01): the demo user reads like a viewer and may not write a layout. */
@QuarkusTest
@TestProfile(RolesIT.DemoLoginOn.class)
class RolesIT {

    public static class DemoLoginOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("castor.env", "dev", "castor.demo-login", "true", "castor.security.auth-cache-ttl", "PT0S");
        }
    }

    private record Write(String method, String path) {}

    @Inject
    DataSource dataSource;

    private static RequestSpecification operator() {
        return given().auth().preemptive().basic("operator", "operator");
    }

    @Test
    void operatorSignsInWithTheOperatorAndViewerRoles() {
        operator()
                .get("/api/v1/me")
                .then()
                .statusCode(200)
                .body("username", equalTo("operator"))
                .body("roles", contains("OPERATOR", "VIEWER"));
    }

    @Test
    void operatorSeesMonitor() {
        for (String path : List.of(
                "/api/v1/me",
                "/api/v1/ports",
                "/api/v1/ports/westports",
                "/api/v1/ports/westports/layouts",
                "/api/v1/catalogue",
                "/api/v1/test-roles/read")) {
            operator().get(path).then().statusCode(200);
        }
    }

    @Test
    void operatorGets403OnEveryLayoutWriteEndpoint() {
        UUID layout = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        String layouts = "/api/v1/layouts/" + layout;
        var writes = List.of(
                new Write("POST", "/api/v1/ports/westports/layouts"),
                new Write("POST", layouts + "/publish"),
                new Write("POST", layouts + "/restore"),
                new Write("DELETE", layouts),
                new Write("POST", layouts + "/validate"),
                new Write("POST", layouts + "/import"),
                new Write("POST", layouts + "/assets"),
                new Write("PATCH", layouts + "/assets/" + asset),
                new Write("DELETE", layouts + "/assets/" + asset),
                new Write("POST", layouts + "/assets/" + asset + "/duplicate"),
                new Write("GET", "/api/v1/ports/westports/layouts/draft"),
                new Write("GET", "/api/v1/reviewers"),
                new Write("GET", "/api/v1/ports/westports/audit"),
                new Write("GET", "/api/v1/test-roles/edit"));
        for (Write w : writes) {
            var request = operator();
            request = w.path().endsWith("/import")
                    ? request.multiPart("file", "layout.geojson", "{}".getBytes(StandardCharsets.UTF_8))
                    : request.contentType(ContentType.JSON).body("{}");
            request.request(w.method(), w.path()).then().statusCode(403);
        }
    }

    @Test
    void operatorHasNoGatewayRightsOfAnAdmin() {
        assertEquals(
                403,
                operator()
                        .contentType(ContentType.JSON)
                        .body("{}")
                        .post("/api/v1/ports/westports/integrations/sources/x/keys")
                        .statusCode());
    }

    @Test
    void roleTableRefusesAnUnknownRole() {
        assertThrows(SQLException.class, () -> {
            try (var c = dataSource.getConnection();
                    var s = c.createStatement()) {
                s.execute("insert into security.user_role (user_id, role) "
                        + "select id, 'SUPERUSER' from security.user_account limit 1");
            }
        });
    }
}
