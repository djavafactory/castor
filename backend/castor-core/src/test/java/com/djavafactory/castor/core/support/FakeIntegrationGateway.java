package com.djavafactory.castor.core.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.Mock;

import com.djavafactory.castor.core.integration.domain.IntegrationGateway;

/**
 * Stands in for castor-gateway in the core tests: records every call and answers with what the test registered. The real
 * client is tested against a real HTTP server in HttpIntegrationGatewayTest.
 */
@Mock
@ApplicationScoped
public class FakeIntegrationGateway implements IntegrationGateway {

    /** One call as core made it; {@code query} is what was asked for, without blank values. */
    public record Call(String method, String portId, String path, Map<String, String> query, JsonNode body) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Object> replies = new ConcurrentHashMap<>();

    public void reset() {
        calls.clear();
        replies.clear();
    }

    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    /** The next answer to {@code method path} (until reset). */
    public void reply(String method, String path, String json) {
        try {
            replies.put(method + " " + path, JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** {@code method path} fails with this exception. */
    public void fail(String method, String path, RuntimeException failure) {
        replies.put(method + " " + path, failure);
    }

    @Override
    public JsonNode get(String portId, String path, Map<String, String> query) {
        Map<String, String> asked = new java.util.LinkedHashMap<>();
        query.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                asked.put(k, v);
            }
        });
        return answer(new Call("GET", portId, path, asked, null));
    }

    @Override
    public JsonNode post(String portId, String path, JsonNode body) {
        return answer(new Call("POST", portId, path, Map.of(), body));
    }

    @Override
    public JsonNode delete(String portId, String path) {
        return answer(new Call("DELETE", portId, path, Map.of(), null));
    }

    private JsonNode answer(Call call) {
        calls.add(call);
        Object reply = replies.get(call.method() + " " + call.path());
        if (reply instanceof RuntimeException failure) {
            throw failure;
        }
        return reply == null ? JSON.createObjectNode() : (JsonNode) reply;
    }
}
