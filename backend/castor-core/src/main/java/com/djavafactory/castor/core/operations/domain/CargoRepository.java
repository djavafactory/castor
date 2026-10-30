package com.djavafactory.castor.core.operations.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CargoRepository {

    Optional<CargoLot> findLot(long portId, String lotCode);

    Optional<CargoLot> findLot(UUID id);

    CargoLot insertLot(CargoLot lot);

    /** Stores the lot if its version is still the stored one; the result carries version + 1. */
    CargoLot updateLot(CargoLot lot);

    /** Appends the movement; false when its event_id is already stored (a replay). */
    boolean appendMovement(CargoMovement movement);

    /** Oldest first. */
    List<CargoMovement> movements(UUID lotId);
}
