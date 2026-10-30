package com.djavafactory.castor.core.shared.domain;

/** How fast the injected clock runs: 1 for the system clock, more for the demo clock of the emulator. */
public record ClockInfo(double speed) {

    public static final ClockInfo SYSTEM = new ClockInfo(1);

    public ClockInfo {
        if (!(speed > 0)) {
            throw new IllegalArgumentException("Clock speed must be positive: " + speed);
        }
    }
}
