package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.application.StateChangeRecorder;
import com.djavafactory.castor.core.operations.application.StateViews;
import com.djavafactory.castor.core.operations.application.StreamHub;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.operations.domain.StreamTicketRepository;
import com.djavafactory.castor.core.operations.infrastructure.StreamFeedLifecycle;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WebSocket (STATE-02 part 3) against a Java client that follows the README "Live stream contract": the client
 * ends equal to a fresh snapshot after a disconnect, tickets work once, and consecutive sequences arrive under load.
 */
@QuarkusTest
class StreamIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final String PORT = "westports";

    @TestHTTPResource("/")
    URL base;

    @Inject
    DataSource dataSource;

    @Inject
    InventoryRepository inventories;

    @Inject
    StateChangeRecorder recorder;

    @Inject
    StateViews views;

    @Inject
    ObjectMapper json;

    @Inject
    StreamFeedLifecycle feed;

    @Inject
    StreamHub hub;

    @Inject
    StreamTicketRepository tickets;

    private long port;
    private UUID pad;
    private final List<ContractClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        LayoutTestSupport.clear(dataSource);
        try (var c = dataSource.getConnection()) {
            port = LayoutTestSupport.portId(c);
            pad = LayoutTestSupport.asset(c, "PAD1", "STORAGE_PAD");
        }
        feed.rebase();
    }

    @AfterEach
    void tearDown() {
        clients.forEach(ContractClient::disconnect);
        LayoutTestSupport.clear(dataSource);
    }

    private static String basic() {
        return "Basic "
                + java.util.Base64.getEncoder()
                        .encodeToString("admin:local-admin-password".getBytes(StandardCharsets.UTF_8));
    }

    private String ticket() {
        return given().header("Authorization", basic())
                .post("/api/v1/ports/" + PORT + "/stream/ticket")
                .then()
                .statusCode(200)
                .extract()
                .path("ticket");
    }

    private URI streamUri(String ticket) {
        return URI.create("ws://localhost:" + base.getPort() + "/api/v1/ports/" + PORT + "/stream?ticket=" + ticket);
    }

    private JsonNode snapshot() throws Exception {
        return json.readTree(given().header("Authorization", basic())
                .get("/api/v1/ports/" + PORT + "/state/snapshot")
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    /** Stores or changes the stock of one commodity and records the change, as a projection will. */
    private void change(String commodity, int quantity) {
        QuarkusTransaction.requiringNew().run(() -> {
            var current = inventories.find(port, pad, commodity);
            var next = new StorageInventory(
                    port,
                    pad,
                    commodity,
                    new Quantity(BigDecimal.valueOf(quantity), Unit.TONNES),
                    T0.plusSeconds(quantity),
                    "SCSS",
                    Quality.SIMULATED,
                    current.map(StorageInventory::version).orElse(0L));
            var saved = current.isPresent() ? inventories.update(next) : inventories.insert(next);
            var dto = views.inventory(saved);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = json.convertValue(dto, Map.class);
            recorder.record(port, "inventory", dto.id(), saved.version(), T0, body);
        });
    }

    /** The browser side of the contract, in Java. */
    private final class ContractClient {

        final LinkedBlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final Map<String, JsonNode> inventoriesById = new TreeMap<>();
        final Map<String, Long> versions = new HashMap<>();
        long applied;
        long lastHeartbeat = -1;
        volatile int closeCode = -1;
        WebSocket socket;

        void connect() throws Exception {
            var listener = new WebSocket.Listener() {
                private final StringBuilder text = new StringBuilder();

                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    text.append(data);
                    if (last) {
                        try {
                            inbox.add(json.readTree(text.toString()));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        text.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closeCode = statusCode;
                    return null;
                }
            };
            socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(streamUri(ticket()), listener)
                    .get(5, TimeUnit.SECONDS);
            // (1) the stream is open and buffering; (2) fetch the snapshot; (3) drop what it already contains
            JsonNode snap = snapshot();
            inventoriesById.clear();
            versions.clear();
            snap.get("inventories")
                    .forEach(item ->
                            store(item.get("id").asText(), item.get("version").asLong(), item));
            applied = snap.get("sequence").asLong();
            lastHeartbeat = -1;
        }

        private void store(String id, long version, JsonNode data) {
            Long known = versions.get(id);
            if (known != null && version <= known) {
                return;
            }
            versions.put(id, version);
            inventoriesById.put(id, data);
        }

        /** Applies every message received so far; a gap is a failure of the server here, never papered over. */
        void drain() {
            JsonNode m;
            while ((m = inbox.poll()) != null) {
                if (m.get("type").asText().equals("heartbeat")) {
                    lastHeartbeat = m.get("sequence").asLong();
                    continue;
                }
                long sequence = m.get("sequence").asLong();
                if (sequence <= applied) {
                    continue;
                }
                assertEquals(applied + 1, sequence, "gap in the stream");
                store(m.get("entityId").asText(), m.get("entityVersion").asLong(), m.get("data"));
                applied = sequence;
            }
        }

        void awaitApplied(long sequence) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                drain();
                assertEquals(sequence, applied);
            });
        }

        void disconnect() {
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            }
        }
    }

    private ContractClient client() throws Exception {
        var c = new ContractClient();
        clients.add(c);
        c.connect();
        return c;
    }

    @Test
    void afterADisconnectAndTenChangesAClientFollowingTheContractEqualsAFreshSnapshot() throws Exception {
        change("COAL", 1);
        ContractClient browser = client();
        change("COAL", 2);
        change("IRON", 3);
        change("SAND", 4);
        browser.awaitApplied(4);

        browser.disconnect();
        for (int i = 0; i < 10; i++) {
            change("COAL", 10 + i);
        }
        ContractClient again = client();
        change("SAND", 99);
        again.awaitApplied(15);

        JsonNode fresh = snapshot();
        assertEquals(15, fresh.get("sequence").asLong());
        Map<String, JsonNode> expected = new TreeMap<>();
        fresh.get("inventories").forEach(item -> expected.put(item.get("id").asText(), item));
        assertEquals(expected.keySet(), again.inventoriesById.keySet());
        expected.forEach((id, item) -> assertTrue(sameJson(item, again.inventoriesById.get(id)), id));
    }

    @Test
    void aHundredConcurrentChangesArriveWithConsecutiveSequences() throws Exception {
        ContractClient browser = client();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int n = i;
                done.add(pool.submit(() -> change("C" + n, n + 1)));
            }
            for (Future<?> f : done) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        browser.awaitApplied(100);
        assertEquals(100, browser.inventoriesById.size());
    }

    @Test
    void aHeartbeatNamesTheLastSequenceSent() throws Exception {
        ContractClient browser = client();
        change("COAL", 1);
        browser.awaitApplied(1);

        hub.heartbeat();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            browser.drain();
            assertEquals(1, browser.lastHeartbeat);
        });
    }

    @Test
    void aTicketWorksOnceAndOnlyForItsPort() throws Exception {
        String ticket = ticket();
        var first = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(streamUri(ticket), new WebSocket.Listener() {})
                .get(5, TimeUnit.SECONDS);
        first.sendClose(WebSocket.NORMAL_CLOSURE, "bye");

        assertEquals(401, handshakeStatus(streamUri(ticket)), "a used ticket");
        assertEquals(401, handshakeStatus(streamUri("not-a-ticket")), "an unknown ticket");
        assertEquals(
                401,
                handshakeStatus(URI.create(streamUri(ticket()).toString().replace("/" + PORT + "/", "/nowhere/"))),
                "a ticket of another port");
        assertEquals(401, handshakeStatus(URI.create(streamUri("x").toString().replace("?ticket=x", ""))), "no ticket");
    }

    @Test
    void anExpiredTicketIsRefused() throws Exception {
        String ticket = "expired-" + UUID.randomUUID();
        String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8)));
        QuarkusTransaction.requiringNew()
                .run(() -> tickets.issue(
                        hash,
                        port,
                        "admin",
                        Instant.now().minusSeconds(60),
                        Instant.now().minusSeconds(120)));

        assertEquals(401, handshakeStatus(streamUri(ticket)));
    }

    @Test
    void aTicketNeedsASignedInUser() {
        given().post("/api/v1/ports/" + PORT + "/stream/ticket").then().statusCode(401);
    }

    private static final java.util.Comparator<JsonNode> NUMBERS_BY_VALUE =
            (x, y) -> x.isNumber() && y.isNumber() ? x.decimalValue().compareTo(y.decimalValue()) : x.equals(y) ? 0 : 1;

    /** Equal as JSON values: 99 and 99.0 are the same number (the stored body drops the trailing zero). */
    private static boolean sameJson(JsonNode a, JsonNode b) {
        return a.equals(NUMBERS_BY_VALUE, b);
    }

    private int handshakeStatus(URI uri) throws Exception {
        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(uri, new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS)
                    .abort();
            return 101;
        } catch (ExecutionException e) {
            var handshake = assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
            assertTrue(handshake.getResponse().statusCode() > 0);
            return handshake.getResponse().statusCode();
        }
    }
}
