package com.djavafactory.castor.core.operations.domain;

import java.util.Optional;
import java.util.UUID;

import com.djavafactory.castor.contracts.Quantity;

/** The assets of the published layout of a port: what an event may talk about. */
public interface PublishedAssets {

    /** The type code of the asset when it is in the published layout of the port; empty otherwise. */
    Optional<String> typeCode(long portId, UUID assetId);

    /** True when the asset is a storage area (an asset type with {@code holds_stock}) of the published layout of the port. */
    default boolean isStorage(long portId, UUID assetId) {
        return false;
    }

    /**
     * The usable capacity the published layout gives the storage asset: capacity x usable ratio, in the unit of the
     * inspector field. Empty when the asset has no value, or its unit is not one a balance is counted in (UNKNOWN).
     */
    default Optional<Quantity> usableCapacity(long portId, UUID assetId) {
        return Optional.empty();
    }
}
