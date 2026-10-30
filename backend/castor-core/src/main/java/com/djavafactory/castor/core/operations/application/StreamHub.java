package com.djavafactory.castor.core.operations.application;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.core.identity.domain.UserRepository;
import com.djavafactory.castor.core.identity.domain.UserSignedOut;
import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.shared.domain.ClockInfo;

/**
 * The browsers connected to this instance. Gets every state change in sequence order from the change feed and sends it
 * to the sessions of its port; sends a heartbeat now and then; closes sessions that must not go on.
 */
@ApplicationScoped
public class StreamHub implements StateChangeSink {

    public static final int RESYNC = 4001;
    public static final String RESYNC_REASON = "resync";
    static final int FORBIDDEN = 4003;

    private final Map<Long, Set<StreamSession>> sessions = new HashMap<>();
    private final Map<Long, Long> last = new HashMap<>();
    private final ObjectMapper json;
    private final InstantSource clock;
    private final ClockInfo clockInfo;
    private final UserRepository users;

    StreamHub(ObjectMapper json, InstantSource clock, ClockInfo clockInfo, UserRepository users) {
        this.json = json;
        this.clock = clock;
        this.clockInfo = clockInfo;
        this.users = users;
    }

    /** A hub for a test or another process, without CDI. */
    public static StreamHub of(ObjectMapper json, InstantSource clock, ClockInfo clockInfo, UserRepository users) {
        return new StreamHub(json, clock, clockInfo, users);
    }

    public synchronized void register(StreamSession session) {
        sessions.computeIfAbsent(session.portId(), p -> new CopyOnWriteArraySet<>())
                .add(session);
    }

    public synchronized void unregister(StreamSession session) {
        var port = sessions.get(session.portId());
        if (port != null) {
            port.remove(session);
        }
    }

    public synchronized int sessionCount() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }

    @Override
    public synchronized void baseline(long portId, long sequence) {
        last.put(portId, sequence);
    }

    @Override
    public synchronized void accept(StateChange change) {
        last.put(change.portId(), change.sequence());
        var port = sessions.get(change.portId());
        if (port == null || port.isEmpty()) {
            return;
        }
        ObjectNode delta = json.createObjectNode();
        delta.put("type", "delta");
        delta.put("sequence", change.sequence());
        delta.put("entityType", change.entityType());
        delta.put("entityId", change.entityId());
        delta.put("entityVersion", change.entityVersion());
        delta.put("occurredAt", change.occurredAt().toString());
        delta.set("data", json.valueToTree(change.body()));
        send(port, delta.toString());
    }

    /** Tells every session the last sequence sent to it and the server clock. */
    public synchronized void heartbeat() {
        var now = clock.instant();
        sessions.forEach((portId, port) -> {
            if (port.isEmpty()) {
                return;
            }
            ObjectNode beat = json.createObjectNode();
            beat.put("type", "heartbeat");
            beat.put("sequence", last.getOrDefault(portId, 0L));
            beat.put("serverTime", now.toString());
            beat.put("clockSpeed", clockInfo.speed());
            send(port, beat.toString());
        });
    }

    /** Closes the sessions of users who are disabled or have no role any more. */
    public void closeUnauthorised() {
        Set<String> names = new HashSet<>();
        synchronized (this) {
            sessions.values().forEach(port -> port.forEach(s -> names.add(s.username())));
        }
        for (String name : names) {
            if (users.activeRoles(name).isEmpty()) {
                closeUser(name, FORBIDDEN, "role lost");
            }
        }
    }

    void onSignedOut(@Observes UserSignedOut event) {
        closeUser(event.username(), 1000, "signed out");
    }

    public void closeUser(String username, int code, String reason) {
        List<StreamSession> doomed = new ArrayList<>();
        synchronized (this) {
            sessions.values()
                    .forEach(port -> port.stream()
                            .filter(s -> s.username().equalsIgnoreCase(username))
                            .forEach(doomed::add));
        }
        doomed.forEach(s -> {
            unregister(s);
            s.close(code, reason);
        });
    }

    private void send(Set<StreamSession> port, String message) {
        for (StreamSession session : port) {
            if (!session.offer(message)) {
                port.remove(session);
                session.close(RESYNC, RESYNC_REASON);
            }
        }
    }
}
