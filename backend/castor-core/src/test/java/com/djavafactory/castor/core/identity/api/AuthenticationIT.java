package com.djavafactory.castor.core.identity.api;

import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Basic auth against the database with the demo users, roles per endpoint group, events and lockout. */
@QuarkusTest
@TestProfile(AuthenticationIT.DemoLoginOn.class)
class AuthenticationIT {

    public static class DemoLoginOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "castor.env", "dev",
                    "castor.demo-login", "true",
                    "castor.security.lockout.window", "PT3S",
                    "castor.security.auth-cache-ttl", "PT0S");
        }
    }

    @Inject
    DataSource dataSource;

    private static RequestSpecification as(String user, String password) {
        return given().auth().preemptive().basic(user, password);
    }

    @Test
    void noCredentialsGives401() {
        given().when().get("/api/v1/me").then().statusCode(401);
        given().when().get("/api/v1/test-roles/read").then().statusCode(401);
    }

    @Test
    void meReturnsUsernameRolesAndDisplayName() {
        as("editor", "editor")
                .when()
                .get("/api/v1/me")
                .then()
                .statusCode(200)
                .body("username", equalTo("editor"))
                .body("roles", contains("EDITOR", "VIEWER"))
                .body("displayName", equalTo("Editor: edits layout drafts"));
    }

    @Test
    void eachRoleGroupGives403ForTheWrongRoleAnd200ForTheRightOne() {
        as("viewer", "viewer").get("/api/v1/test-roles/read").then().statusCode(200);
        as("viewer", "viewer").get("/api/v1/test-roles/edit").then().statusCode(403);
        as("viewer", "viewer").get("/api/v1/test-roles/publish").then().statusCode(403);
        as("editor", "editor").get("/api/v1/test-roles/edit").then().statusCode(200);
        as("editor", "editor").get("/api/v1/test-roles/publish").then().statusCode(403);
        as("editor", "editor").get("/api/v1/test-roles/admin").then().statusCode(403);
        as("admin", "admin").get("/api/v1/test-roles/publish").then().statusCode(200);
        as("admin", "admin").get("/api/v1/test-roles/admin").then().statusCode(200);
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSame() {
        var wrong = as("viewer", "nope").get("/api/v1/me").then().statusCode(401);
        var unknown = as("nobody", "nope").get("/api/v1/me").then().statusCode(401);
        assertEquals(
                wrong.extract().header("WWW-Authenticate"), unknown.extract().header("WWW-Authenticate"));
    }

    @Test
    void demoUsersAreListed() {
        given().when()
                .get("/api/v1/auth/demo-users")
                .then()
                .statusCode(200)
                .body("username", contains("admin", "publisher", "editor", "viewer"))
                .body(
                        "label",
                        containsInAnyOrder(
                                "Admin: full access",
                                "Publisher: publishes and restores layouts",
                                "Editor: edits layout drafts",
                                "Viewer: monitor only"));
    }

    @Test
    void passwordsAreStoredAsBcryptCost12() throws SQLException {
        try (var c = dataSource.getConnection();
                var s = c.createStatement();
                var rs = s.executeQuery("select username, password_hash from security.user_account")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                String hash = rs.getString("password_hash");
                assertTrue(hash.startsWith("$2a$12$") || hash.startsWith("$2b$12$"), hash);
                assertTrue(!hash.equals(rs.getString("username")));
            }
            assertTrue(rows >= 3);
        }
    }

    @Test
    void everyAttemptWritesAnAuthEvent() throws SQLException {
        as("viewer", "viewer").get("/api/v1/me").then().statusCode(200);
        as("viewer", "bad-password").get("/api/v1/me").then().statusCode(401);

        assertTrue(count("viewer", "SUCCESS") >= 1);
        assertTrue(count("viewer", "FAILURE") >= 1);
    }

    @Test
    void fiveFailuresLockTheAccountThenItUnlocksAfterTheWindow() throws Exception {
        String user = "editor";
        for (int i = 0; i < 5; i++) {
            as(user, "wrong-" + i).get("/api/v1/me").then().statusCode(401);
        }
        // locked: even the right password is refused, with the same answer
        as(user, user).get("/api/v1/me").then().statusCode(401);
        as(user, user).get("/api/v1/me").then().statusCode(401);
        assertTrue(count(user, "LOCKED") >= 2);

        Thread.sleep(3500);

        as(user, user).get("/api/v1/me").then().statusCode(200);
        // a successful login reset the counter: four more failures do not lock again
        for (int i = 0; i < 4; i++) {
            as(user, "wrong-" + i).get("/api/v1/me").then().statusCode(401);
        }
        as(user, user).get("/api/v1/me").then().statusCode(200);
    }

    @Test
    void logoutIsRecorded() throws SQLException {
        as("admin", "admin").post("/api/v1/auth/logout").then().statusCode(204);

        assertTrue(count("admin", "LOGOUT") >= 1);
    }

    private int count(String username, String outcome) throws SQLException {
        try (var c = dataSource.getConnection();
                var s = c.prepareStatement(
                        "select count(*) from security.auth_event where username = ? and outcome = ?")) {
            s.setString(1, username);
            s.setString(2, outcome);
            try (var rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
