package com.djavafactory.castor.core.operations.domain;

import java.util.List;
import java.util.Optional;

public interface VesselCallRepository {

    Optional<VesselCall> find(long portId, String callCode);

    List<VesselCall> findAll(long portId);

    /** Stores a new call (id may be null) and returns it with its id and version 0. */
    VesselCall insert(VesselCall call);

    /** Stores the call if its version is still the stored one; the result carries version + 1. */
    VesselCall update(VesselCall call);
}
