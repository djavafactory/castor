package com.djavafactory.castor.contracts;

/** Two quantities of different dimensions were mixed. */
public class UnitMismatchException extends RuntimeException {

    public UnitMismatchException(Unit from, Unit to) {
        super("Cannot convert " + from + " (" + from.dimension() + ") to " + to + " (" + to.dimension() + ")");
    }
}
