package com.djavafactory.castor.contracts;

import java.math.BigDecimal;
import java.util.Objects;

/** A number with its unit. Arithmetic across units throws {@link UnitMismatchException}. */
public record Quantity(BigDecimal value, Unit unit) {

    public Quantity {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value.add(other.in(unit).value), unit);
    }

    public Quantity minus(Quantity other) {
        return new Quantity(value.subtract(other.in(unit).value), unit);
    }

    public Quantity in(Unit target) {
        return new Quantity(unit.convert(value, target), target);
    }
}
