package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.util.List;

import com.djavafactory.castor.contracts.Unit;

/** A call with its event timeline (oldest first) and the cargo progress of each family. */
public record VesselCallDetailDto(VesselCallDto call, List<LineageEntryDto> timeline, List<CargoProgressDto> cargo) {

    /** Moved against registered, in the family's own unit; registered is null while no lot states a quantity. */
    public record CargoProgressDto(String cargoFamily, Unit unit, BigDecimal registered, BigDecimal moved) {}
}
