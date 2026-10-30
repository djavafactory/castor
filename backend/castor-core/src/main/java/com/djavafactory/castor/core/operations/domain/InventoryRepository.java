package com.djavafactory.castor.core.operations.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {

    Optional<StorageInventory> find(long portId, UUID storageAssetId, String commodity);

    /** Locks the row until the end of the transaction, so concurrent adjustments queue up. */
    Optional<StorageInventory> findForUpdate(long portId, UUID storageAssetId, String commodity);

    List<StorageInventory> findByStorage(long portId, UUID storageAssetId);

    StorageInventory insert(StorageInventory inventory);

    /** Stores the inventory if its version is still the stored one; the result carries version + 1. */
    StorageInventory update(StorageInventory inventory);

    /** Appends the adjustment; false when its event_id is already stored (a replay). */
    boolean appendAdjustment(InventoryAdjustment adjustment);

    /** Oldest first. */
    List<InventoryAdjustment> adjustments(long portId, UUID storageAssetId);
}
