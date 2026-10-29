package com.djavafactory.castor.contracts;

/** How a value was obtained (rule R2). Emulator data is always {@link #SIMULATED}. */
public enum Quality {
    OBSERVED,
    INFERRED,
    SIMULATED
}
