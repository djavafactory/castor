package com.djavafactory.castor.core.operations.domain;

import java.util.Objects;

import com.djavafactory.castor.contracts.Unit;

/** A row of ref.cargo_family. A new family is a new row, never a new constant. */
public record CargoFamily(String code, String label, Unit defaultUnit) {

    public CargoFamily {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(defaultUnit, "defaultUnit");
    }
}
