package com.djavafactory.castor.core.operations.application;

import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;

import com.djavafactory.castor.core.audit.application.AuditService;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.operations.api.ReconciliationItemDto;
import com.djavafactory.castor.core.operations.domain.EventLogRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * The review queue of the Data quality panel. Resolving an item records a human decision (who, when, the note) and
 * nothing else: no balance, call or lot changes. Correcting data is an {@code inventory.adjusted} event. Every
 * resolution is audited in the transaction that records it.
 */
@ApplicationScoped
public class ReviewItems {

    static final String AUDIT_KIND = "RECONCILIATION_ITEM";
    static final int MAX_NOTE = 2000;
    static final int LIMIT = 500;

    private final ReconciliationRepository items;
    private final EventLogRepository log;
    private final AuditService audit;
    private final SecurityIdentity identity;
    private final InstantSource clock;

    ReviewItems(
            ReconciliationRepository items,
            EventLogRepository log,
            AuditService audit,
            SecurityIdentity identity,
            InstantSource clock) {
        this.items = items;
        this.log = log;
        this.audit = audit;
        this.identity = identity;
        this.clock = clock;
    }

    /** Newest first; a null status means OPEN, a null kind every kind. */
    @Transactional
    public List<ReconciliationItemDto> list(long portId, ReconciliationStatus status, ReconciliationKind kind) {
        List<ReconciliationItem> found =
                items.findByStatus(portId, status == null ? ReconciliationStatus.OPEN : status, kind, LIMIT);
        Map<UUID, String> types = log.types(found.stream()
                .map(ReconciliationItem::eventId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());
        return found.stream().map(i -> dto(i, types.get(i.eventId()))).toList();
    }

    @Transactional
    public ReconciliationItemDto resolve(String portCode, long portId, long id, String note) {
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            throw new InvalidRequestException("A note is required to resolve an item");
        }
        if (text.length() > MAX_NOTE) {
            throw new InvalidRequestException("The note is longer than " + MAX_NOTE + " characters");
        }
        ReconciliationItem item = items.find(id)
                .filter(i -> i.portId() == portId)
                .orElseThrow(() -> new ResourceNotFoundException("No reconciliation item " + id));
        String actor = identity.getPrincipal().getName();
        if (!items.resolve(id, actor, text, clock.instant())) {
            throw new ConflictException("Reconciliation item " + id + " is already resolved");
        }
        ReconciliationItem resolved = items.find(id).orElseThrow();
        ObjectNode before = JsonNodeFactory.instance
                .objectNode()
                .put("status", item.status().name());
        ObjectNode after = JsonNodeFactory.instance
                .objectNode()
                .put("status", resolved.status().name())
                .put("kind", item.kind().name())
                .put("entityType", item.entityType())
                .put("entityId", item.entityId())
                .put("note", text);
        audit.recordForPort(
                portCode, AuditAction.RECONCILIATION_RESOLVED, AUDIT_KIND, String.valueOf(id), before, after);
        return dto(
                resolved,
                item.eventId() == null
                        ? null
                        : log.types(List.of(item.eventId())).get(item.eventId()));
    }

    private static ReconciliationItemDto dto(ReconciliationItem i, String eventType) {
        return new ReconciliationItemDto(
                i.id(),
                i.kind(),
                i.entityType(),
                i.entityId(),
                i.eventId(),
                eventType,
                i.detail(),
                i.status(),
                i.createdAt(),
                i.resolvedBy(),
                i.resolvedAt(),
                i.note());
    }
}
