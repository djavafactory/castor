package com.djavafactory.castor.core.operations.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.identity.domain.UserRepository;
import com.djavafactory.castor.core.operations.application.StateChangeRecorder;
import com.djavafactory.castor.core.operations.application.StreamHub;
import com.djavafactory.castor.core.operations.application.StreamSession;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.shared.domain.ClockInfo;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The change feed (STATE-02 part 2): two core instances, each with its own hub, see what the other one commits. */
@QuarkusTest
class StateChangeFeedIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    StateChangeRecorder recorder;

    @Inject
    StateChangeRepository changes;

    @Inject
    UserRepository users;

    @Inject
    ObjectMapper json;

    private long port;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    private static final class Collector implements StreamSession {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final long port;

        Collector(long port) {
            this.port = port;
        }

        @Override
        public String username() {
            return "ann";
        }

        @Override
        public long portId() {
            return port;
        }

        @Override
        public boolean offer(String message) {
            messages.add(message);
            return true;
        }

        @Override
        public void close(int code, String reason) {}
    }

    @BeforeEach
    void setUp() throws Exception {
        LayoutTestSupport.clear(dataSource);
        try (var c = dataSource.getConnection()) {
            port = LayoutTestSupport.portId(c);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
        LayoutTestSupport.clear(dataSource);
    }

    /** A second "instance": its own hub and its own feed with its own listening connection. */
    private Collector instance() {
        var hub = StreamHub.of(json, () -> T0, ClockInfo.SYSTEM, users);
        var feed = new StateChangeFeed(dataSource, changes, hub);
        toClose.add(feed);
        feed.start();
        var browser = new Collector(port);
        hub.register(browser);
        return browser;
    }

    private void commit(int n) {
        QuarkusTransaction.requiringNew().run(() -> recorder.record(port, "equipment", "E" + n, n, T0, Map.of("n", n)));
    }

    @Test
    void aChangeCommittedElsewhereReachesTheBrowsersOfThisInstanceWithinOneSecond() throws Exception {
        Collector a = instance();
        Collector b = instance();

        commit(1);

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertEquals(1, a.messages.size());
            assertEquals(1, b.messages.size());
        });
        assertEquals(1, json.readTree(a.messages.get(0)).get("sequence").asLong());
    }

    @Test
    void changesArriveOnceAndInSequenceOrder() throws Exception {
        Collector a = instance();

        for (int i = 1; i <= 30; i++) {
            commit(i);
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(30, a.messages.size()));
        for (int i = 0; i < 30; i++) {
            assertEquals(i + 1, json.readTree(a.messages.get(i)).get("sequence").asLong());
        }
    }

    @Test
    void aFeedThatStartsLaterDeliversOnlyWhatIsCommittedAfterIt() throws Exception {
        commit(1);
        commit(2);
        Collector late = instance();

        commit(3);

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertEquals(1, late.messages.size()));
        assertEquals(3, json.readTree(late.messages.get(0)).get("sequence").asLong());
    }
}
