package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;

import com.djavafactory.castor.contracts.Unit;

/** Cargo of one family handled at a berth during a call: registered is null while no lot states a quantity. */
public record CargoProgress(String cargoFamily, Unit unit, BigDecimal registered, BigDecimal moved) {}
