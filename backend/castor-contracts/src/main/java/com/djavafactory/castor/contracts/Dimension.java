package com.djavafactory.castor.contracts;

/**
 * What a unit measures. Every dimension has exactly one unit, so a value is never converted: tonnes are not pieces and
 * tonnes/h are not vehicles/h (rule R4).
 */
public enum Dimension {
    MASS(false),
    COUNT(false),
    VOLUME(false),
    VEHICLE_COUNT(false),
    TRUCK_COUNT(false),
    LIFT_COUNT(false),
    MASS_RATE(true),
    COUNT_RATE(true),
    VOLUME_RATE(true),
    VEHICLE_RATE(true),
    TRUCK_RATE(true),
    LIFT_RATE(true);

    private final boolean rate;

    Dimension(boolean rate) {
        this.rate = rate;
    }

    /** True for "per hour" dimensions. */
    public boolean isRate() {
        return rate;
    }
}
