package com.djavafactory.castor.core.operations.domain;

import java.util.Optional;
import java.util.UUID;

/** The assets of the published layout of a port: what an event may talk about. */
public interface PublishedAssets {

    /** The type code of the asset when it is in the published layout of the port; empty otherwise. */
    Optional<String> typeCode(long portId, UUID assetId);
}
