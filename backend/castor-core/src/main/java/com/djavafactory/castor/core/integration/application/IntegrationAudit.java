package com.djavafactory.castor.core.integration.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;

/**
 * Writes the audit row of an integration action in a transaction of its own, after the gateway has done the work: the
 * gateway call is not held inside a database transaction.
 */
@ApplicationScoped
public class IntegrationAudit {

    private final AuditService audit;

    public IntegrationAudit(AuditService audit) {
        this.audit = audit;
    }

    @Transactional
    public void record(
            String portCode, AuditAction action, String entityKind, String entityId, JsonNode before, JsonNode after) {
        audit.recordForPort(portCode, action, entityKind, entityId, before, after);
    }
}
