package com.djavafactory.castor.core.audit.application;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import org.jboss.logging.MDC;

import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.audit.domain.AuditRepository;

/**
 * Writes the audit row of a change. It must be called inside the transaction of the change (MANDATORY), so a change
 * that rolls back leaves no audit row and a committed change always has exactly one.
 */
@ApplicationScoped
public class AuditService {

    private static final String SYSTEM = "system";

    private final AuditRepository repository;
    private final SecurityIdentity identity;

    public AuditService(AuditRepository repository, SecurityIdentity identity) {
        this.repository = repository;
        this.identity = identity;
    }

    /**
     * @param before snapshot before the change, null when the entity is new
     * @param after snapshot after the change, null when the entity is gone
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void record(
            AuditAction action,
            String entityKind,
            String entityId,
            UUID layoutVersionId,
            JsonNode before,
            JsonNode after) {
        Object requestId = MDC.get("requestId");
        repository.append(
                actor(),
                action,
                entityKind,
                entityId,
                layoutVersionId,
                before == null ? null : before.toString(),
                after == null ? null : after.toString(),
                requestId == null ? null : requestId.toString());
    }

    /** Records a change that belongs to a port rather than to a layout version, such as an integration action. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void recordForPort(
            String portCode, AuditAction action, String entityKind, String entityId, JsonNode before, JsonNode after) {
        Object requestId = MDC.get("requestId");
        repository.appendForPort(
                portCode,
                actor(),
                action,
                entityKind,
                entityId,
                before == null ? null : before.toString(),
                after == null ? null : after.toString(),
                requestId == null ? null : requestId.toString());
    }

    private String actor() {
        return identity == null || identity.isAnonymous()
                ? SYSTEM
                : identity.getPrincipal().getName();
    }
}
