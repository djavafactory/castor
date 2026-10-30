package com.djavafactory.castor.gateway.adapters.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.CanonicalEventValidator;
import com.djavafactory.castor.contracts.Violation;
import com.djavafactory.castor.gateway.adapters.domain.SourceQualities;
import com.djavafactory.castor.gateway.mapping.domain.MappingException;
import com.djavafactory.castor.gateway.mapping.domain.Mappings;
import com.djavafactory.castor.gateway.normalize.domain.EventIds;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.normalize.domain.ViolationText;

/**
 * What the five adapters share: the envelope they all fill the same way, the check of the finished event against the
 * contract, and the mapping lookups that turn a mapping failure into the rejection the quarantine records.
 */
@ApplicationScoped
public class AdapterSupport {

    /** The part of an event that differs per type. {@code sequence} is the source's own ordering number, or null. */
    record Draft(
            String type,
            String entityType,
            String entityId,
            String aggregateId,
            Instant occurredAt,
            Long sequence,
            ObjectNode payload) {}

    private final Mappings mappings;
    private final SourceQualities qualities;
    private final CanonicalEventValidator validator;
    private final CanonicalEventCodec codec;

    public AdapterSupport(
            Mappings mappings,
            SourceQualities qualities,
            CanonicalEventValidator validator,
            CanonicalEventCodec codec) {
        this.mappings = mappings;
        this.qualities = qualities;
        this.validator = validator;
        this.codec = codec;
    }

    /** The event of the draft, checked against the envelope and payload schemas of its type. */
    CanonicalEvent event(InboxRecord record, Draft draft) throws Rejection {
        CanonicalEvent event = new CanonicalEvent(
                // the normalizer replaces this with the id of (receptionId, index)
                EventIds.of(record.receptionId(), 0).toString(),
                CanonicalEvent.SCHEMA_VERSION,
                record.portId(),
                record.source().toUpperCase(Locale.ROOT),
                record.sourceEventId(),
                draft.sequence(),
                record.receptionId().toString(),
                draft.type(),
                draft.entityType(),
                draft.entityId(),
                draft.aggregateId(),
                draft.occurredAt(),
                record.receivedAt(),
                null,
                qualities.of(record.portId(), record.source()),
                draft.payload());
        List<Violation> violations = validator.validate(codec.encode(event));
        if (!violations.isEmpty()) {
            throw new Rejection(Rejection.SCHEMA_INVALID, ViolationText.describe(violations));
        }
        return event;
    }

    /** A physical asset or call ref of the record's source, mapped as it was at {@code at}. */
    String identity(InboxRecord record, String entityType, String sourceId, Instant at) throws Rejection {
        try {
            return mappings.identity(record.portId(), record.source(), entityType, sourceId, at);
        } catch (MappingException e) {
            throw rejection(e);
        }
    }

    /** A source family or commodity code of the record's source, mapped as it was at {@code at}. */
    String cargoCode(InboxRecord record, String entityType, String sourceCode, Instant at) throws Rejection {
        try {
            return mappings.cargoCode(record.source(), entityType, sourceCode, at);
        } catch (MappingException e) {
            throw rejection(e);
        }
    }

    Mappings mappings() {
        return mappings;
    }

    static Rejection rejection(MappingException e) {
        Rejection.Subject subject = e.sourceId() == null ? null : new Rejection.Subject(e.entityType(), e.sourceId());
        return new Rejection(e.reasonCode(), e.getMessage(), subject);
    }
}
