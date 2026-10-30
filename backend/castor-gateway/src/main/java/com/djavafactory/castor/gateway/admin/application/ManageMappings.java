package com.djavafactory.castor.gateway.admin.application;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.admin.domain.AdminException;
import com.djavafactory.castor.gateway.admin.domain.MappingAdminStore;
import com.djavafactory.castor.gateway.admin.domain.MappingAdminStore.MappingEntry;
import com.djavafactory.castor.gateway.admin.domain.PortId;
import com.djavafactory.castor.gateway.mapping.domain.Mappings;

/**
 * List, create and close identity mappings. A mapping is valid from {@code effectiveFrom} (included) to
 * {@code effectiveTo} (excluded): closing one never deletes it, so an event of an earlier time still maps the way it did.
 * The lookup cache is cleared after a change so the next record sees it at once.
 */
@ApplicationScoped
public class ManageMappings {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 1000;

    static final Set<String> SOURCES = Set.of("cbas", "itap", "scss", "edic", "sensors");
    static final Set<String> ENTITY_TYPES = Set.of("EQUIPMENT", "STORAGE", "BERTH", "GATE", "VESSEL_CALL");
    private static final Set<String> STATES = Set.of("open", "closed", "all");
    private static final int MAX_TEXT = 200;

    /** The values of a new mapping as they arrive; {@code effectiveFrom} is optional and defaults to the epoch. */
    public record NewMapping(
            String sourceSystem,
            String sourceEntityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            Instant effectiveTo) {}

    private final MappingAdminStore store;
    private final Mappings mappings;
    private final InstantSource clock;

    public ManageMappings(MappingAdminStore store, Mappings mappings, InstantSource clock) {
        this.store = store;
        this.mappings = mappings;
        this.clock = clock;
    }

    public List<MappingEntry> list(
            String portId, String source, String entityType, String sourceId, String state, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw AdminException.invalid("INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT);
        }
        String stateName = ReadEvidence.blank(state) == null ? "all" : state;
        if (!STATES.contains(stateName)) {
            throw AdminException.invalid("INVALID_STATE", "state must be open, closed or all");
        }
        return store.list(
                PortId.require(portId),
                new MappingAdminStore.MappingFilter(
                        oneOf("source", source, SOURCES),
                        oneOf("entityType", entityType, ENTITY_TYPES),
                        ReadEvidence.blank(sourceId),
                        stateName,
                        size));
    }

    public MappingEntry create(String portId, NewMapping m, String actor) {
        String system = oneOf("sourceSystem", m.sourceSystem(), SOURCES);
        String type = oneOf("sourceEntityType", m.sourceEntityType(), ENTITY_TYPES);
        if (system == null || type == null) {
            throw AdminException.invalid("REQUIRED", "sourceSystem and sourceEntityType are required");
        }
        String sourceId = text("sourceId", m.sourceId());
        String canonicalId = text("canonicalId", m.canonicalId());
        Instant from = m.effectiveFrom() == null ? Instant.EPOCH : m.effectiveFrom();
        if (m.effectiveTo() != null && !m.effectiveTo().isAfter(from)) {
            throw AdminException.invalid("END_BEFORE_START", "effectiveTo must be after effectiveFrom");
        }
        if (actor == null || actor.isBlank()) {
            throw AdminException.invalid("REQUIRED", "The user who makes the change is required");
        }
        MappingEntry created = store.create(
                PortId.require(portId),
                system,
                type,
                sourceId,
                canonicalId,
                from,
                m.effectiveTo(),
                actor,
                clock.instant());
        mappings.invalidate();
        return created;
    }

    /** Ends the mapping at {@code effectiveTo}, or now when it is null. */
    public MappingEntry close(String portId, long id, Instant effectiveTo) {
        MappingEntry closed =
                store.close(PortId.require(portId), id, effectiveTo == null ? clock.instant() : effectiveTo);
        mappings.invalidate();
        return closed;
    }

    public List<MappingAdminStore.UnresolvedId> unresolved(String portId) {
        return store.unresolved(PortId.require(portId));
    }

    private static String oneOf(String field, String value, Set<String> allowed) {
        String v = ReadEvidence.blank(value);
        if (v == null) {
            return null;
        }
        if (!allowed.contains(v)) {
            throw AdminException.invalid(
                    "INVALID_" + field.toUpperCase(Locale.ROOT), field + " must be one of " + new TreeSet<>(allowed));
        }
        return v;
    }

    private static String text(String field, String value) {
        String v = ReadEvidence.blank(value);
        if (v == null) {
            throw AdminException.invalid("REQUIRED", field + " is required");
        }
        if (v.length() > MAX_TEXT) {
            throw AdminException.invalid("TOO_LONG", field + " must have at most " + MAX_TEXT + " characters");
        }
        return v;
    }
}
