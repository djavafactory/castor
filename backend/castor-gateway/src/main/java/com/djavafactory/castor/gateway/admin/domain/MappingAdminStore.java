package com.djavafactory.castor.gateway.admin.domain;

import java.time.Instant;
import java.util.List;

/** The identity mapping rows as the operators see and change them (the lookup side is {@code MappingStore}). */
public interface MappingAdminStore {

    /** One row of ingest.identity_mapping. {@code effectiveTo} is null while the mapping is open. */
    record MappingEntry(
            long id,
            String sourceSystem,
            String sourceEntityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            Instant effectiveTo,
            String createdBy,
            Instant createdAt) {}

    /**
     * A source id the quarantine could not resolve, and how many records wait on it.
     *
     * @param reasonCode UNKNOWN_MAPPING or AMBIGUOUS_MAPPING
     * @param records quarantined records of the id that no replay has normalized
     */
    record UnresolvedId(
            String source, String entityType, String sourceId, String reasonCode, long records, Instant lastSeenAt) {}

    /**
     * @param state {@code open} (no end date), {@code closed} or {@code all}
     */
    record MappingFilter(String source, String entityType, String sourceId, String state, int limit) {}

    List<MappingEntry> list(String portId, MappingFilter filter);

    /**
     * @throws AdminException CONFLICT when the range overlaps another row of the same source id
     */
    MappingEntry create(
            String portId,
            String sourceSystem,
            String entityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            Instant effectiveTo,
            String createdBy,
            Instant now);

    /**
     * Sets the end date of an open mapping.
     *
     * @throws AdminException NOT_FOUND, or CONFLICT when the mapping is closed already, or INVALID when the end is not
     *     after the start
     */
    MappingEntry close(String portId, long id, Instant effectiveTo);

    List<UnresolvedId> unresolved(String portId);
}
