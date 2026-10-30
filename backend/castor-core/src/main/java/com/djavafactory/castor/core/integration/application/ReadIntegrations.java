package com.djavafactory.castor.core.integration.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.core.integration.domain.IntegrationGateway;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;

/**
 * What the integrations page shows: sources with their counters, the quarantine, one quarantined record (payload with
 * keys and secrets already masked by the gateway), API keys (prefix only) and identity mappings. Reads change nothing and
 * are not audited.
 */
@ApplicationScoped
public class ReadIntegrations {

    private final IntegrationPorts ports;
    private final IntegrationGateway gateway;

    public ReadIntegrations(IntegrationPorts ports, IntegrationGateway gateway) {
        this.ports = ports;
        this.gateway = gateway;
    }

    public JsonNode sources(String portId) {
        return gateway.get(ports.require(portId), "/sources", Map.of());
    }

    public JsonNode quarantine(String portId, String source, String reason, Long before, Integer limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("source", source);
        query.put("reason", reason);
        query.put("before", before == null ? null : before.toString());
        query.put("limit", limit == null ? null : limit.toString());
        return gateway.get(ports.require(portId), "/quarantine", query);
    }

    public JsonNode quarantined(String portId, UUID receptionId) {
        return gateway.get(ports.require(portId), "/quarantine/" + receptionId, Map.of());
    }

    public JsonNode keys(String portId, String source) {
        return gateway.get(ports.require(portId), "/sources/" + segment(source) + "/keys", Map.of());
    }

    public JsonNode mappings(
            String portId, String source, String entityType, String sourceId, String state, Integer limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("source", source);
        query.put("entityType", entityType);
        query.put("sourceId", sourceId);
        query.put("state", state);
        query.put("limit", limit == null ? null : limit.toString());
        return gateway.get(ports.require(portId), "/mappings", query);
    }

    /** Source ids the quarantine could not map, with how many records wait on each (UNKNOWN_MAPPING, AMBIGUOUS_MAPPING). */
    public JsonNode unresolved(String portId) {
        return gateway.get(ports.require(portId), "/mappings/unresolved", Map.of());
    }

    /** A path segment from the browser: only what a source code can be, so nothing reshapes the gateway path. */
    static String segment(String source) {
        if (!source.matches("[a-z]{1,20}")) {
            throw new InvalidRequestException("Unknown source '" + source + "'");
        }
        return source;
    }
}
