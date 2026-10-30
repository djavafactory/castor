package com.djavafactory.castor.core.operations.domain;

import java.util.Optional;
import java.util.UUID;

public interface GateVisitRepository {

    Optional<GateVisit> find(long portId, String truckVisitId, UUID gateAssetId);

    /**
     * Inserts the visit or, when it exists, replaces its times, lane and provenance and raises its version by one. The
     * result carries the stored version (0 for a new visit).
     */
    GateVisit save(GateVisit visit);
}
