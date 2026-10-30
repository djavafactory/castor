package com.djavafactory.castor.core.integration.infrastructure;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.shared.domain.GatewayRefusedException;
import com.djavafactory.castor.core.shared.domain.GatewayUnavailableException;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real HTTP client against a real HTTP server: the token, the paths, the query and every kind of answer. */
class HttpIntegrationGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the server saw of the last request. */
    private record Seen(String method, String uri, String authorization, String contentType, String body) {}

    private HttpServer server;
    private final AtomicReference<Seen> seen = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        seen.set(new Seen(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath()
                        + (exchange.getRequestURI().getRawQuery() == null
                                ? ""
                                : "?" + exchange.getRequestURI().getRawQuery()),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(body, StandardCharsets.UTF_8)));
        byte[] reply = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, reply.length == 0 ? -1 : reply.length);
        if (reply.length > 0) {
            exchange.getResponseBody().write(reply);
        }
        exchange.close();
    }

    private HttpIntegrationGateway gateway(Consumer<Map<String, String>> more) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("castor.env", "test");
        values.put("castor.port-code", "westports");
        values.put("castor.admin.password", "a-long-enough-password");
        values.put(
                "castor.gateway.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        values.put("castor.gateway.service-token", "the-service-token");
        values.put("castor.gateway.timeout", "PT2S");
        more.accept(values);
        CastorConfig config = new SmallRyeConfigBuilder()
                .withMapping(CastorConfig.class)
                .withDefaultValues(values)
                .build()
                .getConfigMapping(CastorConfig.class);
        return new HttpIntegrationGateway(config, JSON);
    }

    private HttpIntegrationGateway gateway() {
        return gateway(v -> {});
    }

    @Test
    void aGetSendsTheServiceTokenAsABearerTokenToTheInternalPathOfThePort() {
        answer = "{\"items\":[1]}";

        JsonNode result = gateway().get("westports", "/sources", Map.of());

        assertEquals(1, result.get("items").size());
        assertEquals("GET", seen.get().method());
        assertEquals("/internal/v1/ports/westports/sources", seen.get().uri());
        assertEquals("Bearer the-service-token", seen.get().authorization());
    }

    @Test
    void theQueryIsEncodedAndBlankValuesAreLeftOut() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("sourceId", "B 9&x=y");
        query.put("source", null);
        query.put("reason", " ");
        query.put("limit", "5");

        gateway().get("westports", "/mappings", query);

        assertEquals(
                "/internal/v1/ports/westports/mappings?sourceId=B+9%26x%3Dy&limit=5",
                seen.get().uri());
    }

    @Test
    void aPostSendsItsJsonAndAnEmptyAnswerIsNull() throws Exception {
        status = 204;
        answer = "";

        JsonNode result = gateway().post("westports", "/mappings", JSON.readTree("{\"sourceId\":\"B9\"}"));

        assertTrue(result.isNull());
        assertEquals("POST", seen.get().method());
        assertEquals("application/json", seen.get().contentType());
        assertEquals("{\"sourceId\":\"B9\"}", seen.get().body());
    }

    @Test
    void aPostWithoutABodyAndADeleteGoToTheirPaths() {
        gateway().post("westports", "/quarantine/x/replay", null);
        assertEquals(
                "/internal/v1/ports/westports/quarantine/x/replay", seen.get().uri());
        assertEquals("", seen.get().body());

        gateway().delete("westports", "/sources/cbas/keys/7");
        assertEquals("DELETE", seen.get().method());
        assertEquals(
                "/internal/v1/ports/westports/sources/cbas/keys/7", seen.get().uri());
    }

    @Test
    void aRefusalCarriesTheStatusTheCodeAndTheMessageOfTheGateway() {
        for (int refused : new int[] {400, 404, 409, 422}) {
            status = refused;
            answer = "{\"title\":\"x\",\"detail\":\"Cannot do that\",\"errors\":[{\"code\":\"RAW_EXPIRED\"}]}";

            var e = assertThrows(GatewayRefusedException.class, () -> gateway().post("westports", "/x", null));

            assertEquals(refused, e.status());
            assertEquals("RAW_EXPIRED", e.code());
            assertEquals("Cannot do that", e.getMessage());
        }
    }

    @Test
    void aRefusalThatIsNotProblemDetailsStillKeepsItsStatus() {
        status = 409;
        answer = "<html>conflict</html>";

        var e = assertThrows(GatewayRefusedException.class, () -> gateway().post("westports", "/x", null));

        assertEquals(409, e.status());
        assertEquals(null, e.code());
    }

    @Test
    void aRefusedTokenAndAServerErrorAreUnavailableAndNeverRevealTheirBody() {
        for (int failed : new int[] {401, 403, 500, 502, 503}) {
            status = failed;
            answer = "{\"detail\":\"secret internals\"}";

            var e = assertThrows(
                    GatewayUnavailableException.class, () -> gateway().get("westports", "/x", Map.of()));

            assertFalse(e.getMessage().contains("secret"), e.getMessage());
        }
    }

    @Test
    void aGatewayThatIsNotThereOrAnswersGarbageIsUnavailable() {
        var down = gateway(v -> v.put("castor.gateway.url", "http://127.0.0.1:1"));
        assertThrows(GatewayUnavailableException.class, () -> down.get("westports", "/x", Map.of()));

        answer = "not json";
        status = 200;
        assertThrows(GatewayUnavailableException.class, () -> gateway().get("westports", "/x", Map.of()));
    }

    @Test
    void aSlowGatewayIsUnavailableAfterTheTimeout() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        var slow = gateway(v -> v.put("castor.gateway.timeout", "PT0.3S"));

        assertThrows(GatewayUnavailableException.class, () -> slow.get("westports", "/x", Map.of()));
    }
}
