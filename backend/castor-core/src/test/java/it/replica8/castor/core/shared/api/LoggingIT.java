package it.replica8.castor.core.shared.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LoggingIT {

    private static final List<String> MDC_KEYS =
            List.of("requestId", "username", "route", "status", "durationMs", "errorId");

    /** What a log line carried when it was written. The MDC is read on the logging thread, where it is still set. */
    private record Line(String message, Level level, Throwable thrown, Map<String, String> mdc) {}

    private final List<Line> lines = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            Map<String, String> mdc = new HashMap<>();
            for (String key : MDC_KEYS) {
                Object value = MDC.get(key);
                if (value != null) {
                    mdc.put(key, value.toString());
                }
            }
            lines.add(new Line(
                    new java.util.logging.SimpleFormatter().formatMessage(record),
                    record.getLevel(),
                    record.getThrown(),
                    mdc));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private final java.util.logging.Logger root = java.util.logging.Logger.getLogger("");

    @BeforeEach
    void attach() {
        root.addHandler(capture);
    }

    @AfterEach
    void detach() {
        root.removeHandler(capture);
    }

    @Test
    void failedRequestCanBeFoundByTheErrorIdShownToTheUser() {
        Response response = given().when().get("/test-problems/boom");
        String errorId = response.jsonPath().getString("errorId");
        String requestId = response.getHeader("X-Request-Id");

        Line logged = lines.stream()
                .filter(l -> l.message().contains(errorId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line carries error id " + errorId));

        assertEquals(Level.SEVERE, logged.level());
        assertNotNull(logged.thrown(), "full stack trace goes to the log");
        assertEquals(requestId, logged.mdc().get("requestId"));
        assertEquals(errorId, logged.mdc().get("errorId"));
    }

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void finishedRequestLineCarriesRequestIdUserRouteStatusAndDuration() {
        Response response = given().when().get("/api/v1/layouts");

        Line line = lines.stream()
                .filter(l -> l.message().startsWith("GET /api/v1/layouts ->"))
                .findFirst()
                .orElseThrow();

        assertEquals(response.getHeader("X-Request-Id"), line.mdc().get("requestId"));
        assertEquals("viewer", line.mdc().get("username"));
        assertEquals("GET /api/v1/layouts", line.mdc().get("route"));
        assertEquals("200", line.mdc().get("status"));
        assertNotNull(line.mdc().get("durationMs"));
    }

    @Test
    @TestSecurity(user = "rahmat", roles = "ADMIN")
    void usernameIsLoggedForSignedInUsers() {
        given().when().get("/api/v1/layouts");

        assertTrue(lines.stream().anyMatch(l -> "rahmat".equals(l.mdc().get("username"))));
    }

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    void identifiersInPathsDoNotMakeNewRoutes() {
        given().when().get("/api/v1/layouts/9c1f2e3a-0000-4000-8000-000000000001/assets");

        assertTrue(lines.stream()
                .anyMatch(l -> "GET /api/v1/layouts/{id}/assets".equals(l.mdc().get("route"))));
    }

    @Test
    void secretsAreNeverLogged() {
        given().header("Authorization", "Bearer super-secret-token-123")
                .header("Cookie", "session=cookie-secret-456")
                .contentType(ContentType.JSON)
                .body("{\"username\":\"u\",\"password\":\"hunter2-secret\"}")
                .when()
                .post("/test-problems/validation");
        given().header("Authorization", "Bearer super-secret-token-123")
                .queryParam("password", "hunter2-secret")
                .when()
                .get("/test-problems/boom");

        assertFalse(lines.isEmpty());
        for (Line l : lines) {
            String text = l.message() + " " + l.mdc();
            assertFalse(text.contains("super-secret-token-123"), text);
            assertFalse(text.contains("cookie-secret-456"), text);
            assertFalse(text.contains("hunter2-secret"), text);
        }
    }
}
