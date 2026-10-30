package com.djavafactory.castor.core.operations.api;

import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.UserData.TypedKey;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;

import com.djavafactory.castor.core.operations.application.StreamHub;
import com.djavafactory.castor.core.operations.application.StreamSession;

/**
 * The live stream of a port: deltas and heartbeats go out, nothing comes in. The hub decides what is sent; this class
 * only connects a WebSocket to it and counts the messages each session has waiting.
 */
@WebSocket(path = "/api/v1/ports/{portId}/stream")
public class StreamEndpoint {

    static final TypedKey<String> USERNAME = TypedKey.forString("castor.stream.user");
    static final TypedKey<Long> PORT_ID = TypedKey.forLong("castor.stream.port");
    private static final TypedKey<Session> SESSION = new TypedKey<>("castor.stream.session");

    private final StreamHub hub;
    private final int maxQueue;

    StreamEndpoint(StreamHub hub) {
        this.hub = hub;
        this.maxQueue = hub.maxQueue();
    }

    @OnOpen
    void open(WebSocketConnection connection) {
        var session = new Session(
                connection,
                connection.userData().get(USERNAME),
                connection.userData().get(PORT_ID));
        connection.userData().put(SESSION, session);
        hub.register(session);
    }

    @OnClose
    void close(WebSocketConnection connection) {
        Session session = connection.userData().get(SESSION);
        if (session != null) {
            hub.unregister(session);
        }
    }

    private final class Session implements StreamSession {

        private final WebSocketConnection connection;
        private final String username;
        private final long portId;
        private final AtomicInteger pending = new AtomicInteger();

        Session(WebSocketConnection connection, String username, long portId) {
            this.connection = connection;
            this.username = username;
            this.portId = portId;
        }

        @Override
        public String username() {
            return username;
        }

        @Override
        public long portId() {
            return portId;
        }

        @Override
        public boolean offer(String message) {
            if (pending.incrementAndGet() > maxQueue) {
                pending.decrementAndGet();
                return false;
            }
            connection
                    .sendText(message)
                    .onTermination()
                    .invoke(pending::decrementAndGet)
                    .subscribe()
                    .with(done -> {}, failure -> connection.close().subscribe().with(x -> {}, y -> {}));
            return true;
        }

        @Override
        public void close(int code, String reason) {
            connection.close(new CloseReason(code, reason)).subscribe().with(done -> {}, failure -> {});
        }
    }
}
