package com.djavafactory.castor.core.identity.api;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Default profile: demo login is off, so there are no demo users and the endpoint does not exist. */
@QuarkusTest
class DemoLoginOffIT {

    @Inject
    DataSource dataSource;

    @Test
    void demoUsersEndpointAnswers404() {
        given().when().get("/api/v1/auth/demo-users").then().statusCode(404);
    }

    @Test
    void demoUsersAreNotCreatedAndTheBootstrapAdminExistsOnce() throws Exception {
        try (var c = dataSource.getConnection();
                var s = c.createStatement();
                var rs = s.executeQuery(
                        "select count(*) filter (where username = 'admin'), "
                                + "count(*) filter (where username in ('publisher', 'editor', 'operator', 'viewer')) from security.user_account")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(0, rs.getInt(2), "no demo users");
        }
    }

    @Test
    void bootstrapAdminCanSignIn() {
        given().auth()
                .preemptive()
                .basic("admin", "local-admin-password")
                .when()
                .get("/api/v1/me")
                .then()
                .statusCode(200);
    }
}
