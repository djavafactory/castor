package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The reads behind the snapshot and the state endpoints that the per-entity repositories do not offer. */
public interface StateReadRepository {

    /**
     * Makes the rest of the caller's transaction read one frozen picture of the database (repeatable read, read only).
     * Must be the first statement of the transaction.
     */
    void beginConsistentRead();

    /** Every inventory row of the port. */
    List<StorageInventory> inventories(long portId);

    /** Visits with an entry and no exit yet. */
    long openGateVisits(long portId);

    long openReconciliationItems(long portId);

    /** Calls that are not over, plus those over whose last change is not before {@code recentSince}. */
    List<VesselCall> callsForSnapshot(long portId, Instant recentSince);

    /** Calls whose time span overlaps [from, to]; null bounds are open. Newest first, at most {@code limit}. */
    List<VesselCall> searchCalls(long portId, VesselStatus status, Instant from, Instant to, int limit);

    Optional<VesselCall> findCall(long portId, UUID id);

    boolean assetExists(long portId, UUID assetId);

    /** Cargo moved to or from the berth between the two instants, per family and unit. */
    List<CargoProgress> cargoProgress(long portId, UUID berthAssetId, Instant from, Instant to);

    /** Canonical events whose aggregate or entity is one of the ids, newest first. */
    List<LineageEntry> lineage(long portId, List<String> entityIds, int limit);
}
