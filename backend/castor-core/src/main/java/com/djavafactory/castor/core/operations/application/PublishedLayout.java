package com.djavafactory.castor.core.operations.application;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.PortExtents;
import com.djavafactory.castor.core.operations.domain.PublishedAssets;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

/** The checks of an event against the port: the assets of the published layout and the port extent. */
@ApplicationScoped
public class PublishedLayout {

    private static final String BERTH = "BERTH";

    private final PublishedAssets assets;
    private final PortExtents extents;

    PublishedLayout(PublishedAssets assets, PortExtents extents) {
        this.assets = assets;
        this.extents = extents;
    }

    /** The asset the id names; UNKNOWN_ENTITY when it is not a published asset of the port. */
    public UUID requireAsset(long portId, String id) {
        return require(portId, id, null, "asset");
    }

    public UUID requireBerth(long portId, String id) {
        return require(portId, id, BERTH, "berth");
    }

    public void requireInExtent(long portId, double lon, double lat) {
        if (!extents.covers(portId, lon, lat)) {
            throw new RejectedEventException(
                    DeadLetterReason.OUT_OF_EXTENT, "Position " + lon + ", " + lat + " is outside the port extent");
        }
    }

    private UUID require(long portId, String id, String typeCode, String what) {
        UUID asset;
        try {
            asset = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RejectedEventException(DeadLetterReason.UNKNOWN_ENTITY, "'" + id + "' is not a " + what + " id");
        }
        String type = assets.typeCode(portId, asset)
                .orElseThrow(() -> new RejectedEventException(
                        DeadLetterReason.UNKNOWN_ENTITY, "No " + what + " " + id + " in the published layout"));
        if (typeCode != null && !typeCode.equals(type)) {
            throw new RejectedEventException(
                    DeadLetterReason.UNKNOWN_ENTITY, "Asset " + id + " is a " + type + ", not a " + typeCode);
        }
        return asset;
    }
}
