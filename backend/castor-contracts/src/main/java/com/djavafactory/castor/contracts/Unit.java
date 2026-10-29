package com.djavafactory.castor.contracts;

import java.math.BigDecimal;
import java.util.Optional;

/** Every unit code a canonical event may carry. The code is the enum name, spelled the same in JSON and in schemas. */
public enum Unit {
    TONNES(Dimension.MASS),
    PIECES(Dimension.COUNT),
    CUBIC_METRES(Dimension.VOLUME),
    VEHICLES(Dimension.VEHICLE_COUNT),
    TRUCKS(Dimension.TRUCK_COUNT),
    LIFTS(Dimension.LIFT_COUNT),
    TONNES_PER_HOUR(Dimension.MASS_RATE),
    PIECES_PER_HOUR(Dimension.COUNT_RATE),
    CUBIC_METRES_PER_HOUR(Dimension.VOLUME_RATE),
    VEHICLES_PER_HOUR(Dimension.VEHICLE_RATE),
    TRUCKS_PER_HOUR(Dimension.TRUCK_RATE),
    LIFTS_PER_HOUR(Dimension.LIFT_RATE);

    private static final String PER_HOUR = "_PER_HOUR";

    private final Dimension dimension;

    Unit(Dimension dimension) {
        this.dimension = dimension;
    }

    public Dimension dimension() {
        return dimension;
    }

    public boolean isRate() {
        return dimension.isRate();
    }

    /** The unit of a JSON code. Unknown codes are rejected, never guessed. */
    public static Unit parse(String code) {
        if (code != null) {
            for (Unit unit : values()) {
                if (unit.name().equals(code)) {
                    return unit;
                }
            }
        }
        throw new IllegalArgumentException("Unknown unit code: " + code);
    }

    /** The rate of this amount unit (TONNES gives TONNES_PER_HOUR); empty for a rate. */
    public Optional<Unit> perHour() {
        return isRate() ? Optional.empty() : Optional.of(valueOf(name() + PER_HOUR));
    }

    /** The amount unit of this rate (TONNES_PER_HOUR gives TONNES); empty for an amount. */
    public Optional<Unit> amount() {
        if (!isRate()) {
            return Optional.empty();
        }
        return Optional.of(valueOf(name().substring(0, name().length() - PER_HOUR.length())));
    }

    /**
     * Re-expresses a value in the target unit. Only the identity exists: a different unit is a different dimension,
     * so the value cannot be converted and is rejected.
     */
    public BigDecimal convert(BigDecimal value, Unit target) {
        if (target != this) {
            throw new UnitMismatchException(this, target);
        }
        return value;
    }
}
