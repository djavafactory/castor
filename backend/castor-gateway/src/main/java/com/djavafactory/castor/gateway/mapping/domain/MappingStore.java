package com.djavafactory.castor.gateway.mapping.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.djavafactory.castor.contracts.Unit;

/** The mapping tables of schema ingest and the published assets of castor-core, as the gateway sees them. */
public interface MappingStore {

    /** Every row (any time) for a source id of a physical asset or a call ref. */
    List<MappingRow> identity(String portId, String sourceSystem, String entityType, String sourceId);

    /** Every row (any time) for a source family or commodity code. */
    List<MappingRow> cargo(String sourceSystem, String entityType, String sourceId);

    /** The units a canonical cargo family may be measured in; empty for a family the gateway has no rule for. */
    Set<Unit> unitsOf(String familyCode);

    /**
     * The codes and ids of the assets in the current published layout of the port, empty when there is none yet.
     *
     * @throws AssetDirectoryUnavailableException castor-core has not created the view (or its grant) yet
     */
    Map<String, String> publishedAssets(String portId);

    /**
     * Inserts the row unless the source id has a row already, at any time: a mapping an operator changed is never put
     * back. Returns whether a row was inserted.
     */
    boolean insertIfMissing(
            String portId,
            String sourceSystem,
            String entityType,
            String sourceId,
            String canonicalId,
            Instant effectiveFrom,
            String createdBy,
            Instant createdAt);

    /** castor-core's view layout.published_asset cannot be read. */
    final class AssetDirectoryUnavailableException extends RuntimeException {

        public AssetDirectoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
