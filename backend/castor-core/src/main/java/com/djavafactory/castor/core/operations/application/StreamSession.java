package com.djavafactory.castor.core.operations.application;

/** One browser connection as the hub sees it; the WebSocket adapter implements it. */
public interface StreamSession {

    String username();

    long portId();

    /** Queues the text message for sending; false when the session already has too many messages waiting. */
    boolean offer(String message);

    void close(int code, String reason);
}
