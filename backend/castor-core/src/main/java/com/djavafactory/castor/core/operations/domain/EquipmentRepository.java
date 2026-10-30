package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EquipmentRepository {

    Optional<EquipmentState> find(long portId, UUID assetId);

    List<EquipmentState> findAll(long portId);

    EquipmentState insert(EquipmentState state);

    /** Stores the state if its version is still the stored one; the result carries version + 1. */
    EquipmentState update(EquipmentState state);

    Optional<EquipmentStatusInterval> openInterval(long portId, UUID assetId);

    void startInterval(EquipmentStatusInterval interval);

    /** Closes the open interval of the machine at the given time; false when none is open. */
    boolean closeInterval(long portId, UUID assetId, Instant endedAt);

    /** The interval that covers the instant (start included, end excluded), open or closed. */
    Optional<EquipmentStatusInterval> intervalAt(long portId, UUID assetId, Instant at);

    /** Gives the interval that started at {@code startedAt} the end time; false when there is none. */
    boolean endInterval(long portId, UUID assetId, Instant startedAt, Instant endedAt);

    /** Newest first. */
    List<EquipmentStatusInterval> intervals(long portId, UUID assetId, int limit);
}
