package com.djavafactory.castor.core.integration.application;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;

import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.integration.domain.IntegrationGateway;

/**
 * Everything an administrator can do to an integration: replay a quarantined record, create, rotate and revoke API keys,
 * create and close identity mappings. The gateway does the work; core then writes one audit row for the action, with who
 * did it. An API key is never written to the audit trail, only its id and prefix.
 */
@ApplicationScoped
public class ChangeIntegrations {

    static final String RECORD = "INTEGRATION_RECORD";
    static final String API_KEY = "API_KEY";
    static final String MAPPING = "IDENTITY_MAPPING";

    /** The values of a new identity mapping; {@code effectiveFrom} defaults to the epoch, {@code effectiveTo} to none. */
    public record NewMapping(
            String sourceSystem,
            String sourceEntityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    private final IntegrationPorts ports;
    private final IntegrationGateway gateway;
    private final IntegrationAudit audit;
    private final SecurityIdentity identity;

    public ChangeIntegrations(
            IntegrationPorts ports, IntegrationGateway gateway, IntegrationAudit audit, SecurityIdentity identity) {
        this.ports = ports;
        this.gateway = gateway;
        this.audit = audit;
        this.identity = identity;
    }

    /** Replays a quarantined record: a new inbox row linked to the original, which is not changed. */
    public JsonNode replay(String portId, UUID receptionId) {
        String port = ports.require(portId);
        JsonNode replay = gateway.post(port, "/quarantine/" + receptionId + "/replay", null);
        audit.record(port, AuditAction.INTEGRATION_REPLAYED, RECORD, receptionId.toString(), null, replay);
        return replay;
    }

    /** The answer holds the new key, once. */
    public JsonNode createKey(String portId, String source) {
        String port = ports.require(portId);
        JsonNode created = gateway.post(port, "/sources/" + ReadIntegrations.segment(source) + "/keys", null);
        audit.record(
                port,
                AuditAction.INTEGRATION_KEY_CREATED,
                API_KEY,
                source + "/" + created.path("id").asText(),
                null,
                withoutKey(source, created));
        return created;
    }

    /** Revokes the key and issues a new one; the answer holds the new key, once. */
    public JsonNode rotateKey(String portId, String source, long keyId) {
        String port = ports.require(portId);
        JsonNode rotated =
                gateway.post(port, "/sources/" + ReadIntegrations.segment(source) + "/keys/" + keyId + "/rotate", null);
        ObjectNode before = JsonNodeFactory.instance.objectNode();
        before.put("source", source);
        before.put("keyId", keyId);
        audit.record(
                port,
                AuditAction.INTEGRATION_KEY_ROTATED,
                API_KEY,
                source + "/" + keyId,
                before,
                withoutKey(source, rotated));
        return rotated;
    }

    public JsonNode revokeKey(String portId, String source, long keyId) {
        String port = ports.require(portId);
        JsonNode revoked = gateway.delete(port, "/sources/" + ReadIntegrations.segment(source) + "/keys/" + keyId);
        audit.record(
                port,
                AuditAction.INTEGRATION_KEY_REVOKED,
                API_KEY,
                source + "/" + keyId,
                null,
                withoutKey(source, revoked));
        return revoked;
    }

    public JsonNode createMapping(String portId, NewMapping mapping) {
        String port = ports.require(portId);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("sourceSystem", mapping.sourceSystem());
        body.put("sourceEntityType", mapping.sourceEntityType());
        body.put("sourceId", mapping.sourceId());
        body.put("canonicalId", mapping.canonicalId());
        body.put(
                "effectiveFrom",
                mapping.effectiveFrom() == null ? null : mapping.effectiveFrom().toString());
        body.put(
                "effectiveTo",
                mapping.effectiveTo() == null ? null : mapping.effectiveTo().toString());
        body.put("actor", identity.getPrincipal().getName());
        JsonNode created = gateway.post(port, "/mappings", body);
        audit.record(
                port,
                AuditAction.INTEGRATION_MAPPING_CREATED,
                MAPPING,
                created.path("id").asText(),
                null,
                created);
        return created;
    }

    /** Ends the mapping at {@code effectiveTo}, or now when it is null; it is never deleted. */
    public JsonNode closeMapping(String portId, long id, Instant effectiveTo) {
        String port = ports.require(portId);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("effectiveTo", effectiveTo == null ? null : effectiveTo.toString());
        JsonNode closed = gateway.post(port, "/mappings/" + id + "/close", body);
        audit.record(port, AuditAction.INTEGRATION_MAPPING_CLOSED, MAPPING, Long.toString(id), null, closed);
        return closed;
    }

    /** What the audit trail keeps of a key: which one it is, never the key. */
    private static JsonNode withoutKey(String source, JsonNode key) {
        ObjectNode kept = JsonNodeFactory.instance.objectNode();
        kept.put("source", source);
        for (String field : new String[] {"id", "prefix", "origin", "createdAt", "revokedAt"}) {
            if (key.hasNonNull(field)) {
                kept.set(field, key.get(field));
            }
        }
        return kept;
    }
}
