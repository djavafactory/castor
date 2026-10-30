package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.identity.domain.Role;
import com.djavafactory.castor.core.identity.domain.UserRepository;
import com.djavafactory.castor.core.identity.domain.UserSignedOut;
import com.djavafactory.castor.core.identity.domain.UserSummary;
import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.shared.domain.ClockInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the hub sends and when it closes a session, without a socket. */
class StreamHubTest {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class FakeSession implements StreamSession {
        final String user;
        final long port;
        final int capacity;
        final List<String> received = new CopyOnWriteArrayList<>();
        final List<String> closed = new CopyOnWriteArrayList<>();

        FakeSession(String user, long port, int capacity) {
            this.user = user;
            this.port = port;
            this.capacity = capacity;
        }

        @Override
        public String username() {
            return user;
        }

        @Override
        public long portId() {
            return port;
        }

        @Override
        public boolean offer(String message) {
            if (received.size() >= capacity) {
                return false;
            }
            received.add(message);
            return true;
        }

        @Override
        public void close(int code, String reason) {
            closed.add(code + " " + reason);
        }

        JsonNode message(int i) throws Exception {
            return JSON.readTree(received.get(i));
        }
    }

    private final Set<String> activeUsers = new java.util.HashSet<>(Set.of("ann"));

    private final UserRepository users = new UserRepository() {
        @Override
        public Optional<UserSummary> findByUsername(String username) {
            return Optional.empty();
        }

        @Override
        public Set<Role> activeRoles(String username) {
            return activeUsers.contains(username) ? Set.of(Role.VIEWER) : Set.of();
        }

        @Override
        public List<UserSummary> findReviewers() {
            return List.of();
        }
    };

    private final StreamHub hub = StreamHub.of(JSON, () -> T0, new ClockInfo(60), users);

    private static StateChange change(long port, long sequence) {
        return new StateChange(port, sequence, "equipment", "e1", sequence, T0, Map.of("status", "IDLE"), null);
    }

    @Test
    void aDeltaCarriesTheChangeAndGoesOnlyToThePortOfTheChange() throws Exception {
        var mine = new FakeSession("ann", 1, 10);
        var other = new FakeSession("ann", 2, 10);
        hub.register(mine);
        hub.register(other);

        hub.accept(change(1, 7));

        assertEquals(1, mine.received.size());
        assertEquals(0, other.received.size());
        var delta = mine.message(0);
        assertEquals("delta", delta.get("type").asText());
        assertEquals(7, delta.get("sequence").asLong());
        assertEquals("equipment", delta.get("entityType").asText());
        assertEquals("e1", delta.get("entityId").asText());
        assertEquals(7, delta.get("entityVersion").asLong());
        assertEquals("2026-10-30T08:00:00Z", delta.get("occurredAt").asText());
        assertEquals("IDLE", delta.get("data").get("status").asText());
    }

    @Test
    void aHeartbeatNamesTheLastSequenceSentAndTheClock() throws Exception {
        var session = new FakeSession("ann", 1, 10);
        hub.register(session);
        hub.baseline(1, 4);
        hub.heartbeat();
        hub.accept(change(1, 5));
        hub.heartbeat();

        var first = session.message(0);
        assertEquals("heartbeat", first.get("type").asText());
        assertEquals(4, first.get("sequence").asLong());
        assertEquals("2026-10-30T08:00:00Z", first.get("serverTime").asText());
        assertEquals(60.0, first.get("clockSpeed").asDouble());
        assertEquals(5, session.message(2).get("sequence").asLong());
    }

    @Test
    void aSessionWhoseQueueIsFullIsClosedWithResync() {
        var slow = new FakeSession("ann", 1, 2);
        var fast = new FakeSession("ann", 1, 100);
        hub.register(slow);
        hub.register(fast);

        for (int i = 1; i <= 3; i++) {
            hub.accept(change(1, i));
        }

        assertEquals(List.of("4001 resync"), slow.closed);
        assertEquals(2, slow.received.size());
        assertEquals(3, fast.received.size());
        assertEquals(1, hub.sessionCount());
    }

    @Test
    void aUserWhoLostEveryRoleIsClosedAndOthersStay() {
        var gone = new FakeSession("bob", 1, 10);
        var stays = new FakeSession("ann", 1, 10);
        hub.register(gone);
        hub.register(stays);

        hub.closeUnauthorised();

        assertEquals(List.of("4003 role lost"), gone.closed);
        assertTrue(stays.closed.isEmpty());
        assertEquals(1, hub.sessionCount());
    }

    @Test
    void signingOutClosesTheSessionsOfThatUser() {
        var ann = new FakeSession("ann", 1, 10);
        var other = new FakeSession("bob", 1, 10);
        hub.register(ann);
        hub.register(other);

        hub.onSignedOut(new UserSignedOut("Ann"));

        assertEquals(1, ann.closed.size());
        assertTrue(other.closed.isEmpty());
        assertEquals(new ArrayList<>(List.of()), ann.received);
    }
}
