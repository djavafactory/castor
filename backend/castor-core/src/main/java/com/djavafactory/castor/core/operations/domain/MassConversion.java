package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import com.djavafactory.castor.contracts.Quantity;

/**
 * Mass in tonnes of a quantity, when it can honestly be known (R3, R4). A mass stays a mass; a volume becomes a mass
 * only with the density the event carried; a count has no mass here. Everything else is unknown, never a guess from a
 * reference table.
 */
public final class MassConversion {

    private static final BigDecimal KG_PER_TONNE = new BigDecimal(1000);

    private MassConversion() {}

    public static Optional<BigDecimal> tonnes(Quantity quantity, BigDecimal densityKgM3) {
        if (quantity == null) {
            return Optional.empty();
        }
        return switch (quantity.unit()) {
            case TONNES -> Optional.of(quantity.value());
            case CUBIC_METRES ->
                densityKgM3 == null
                        ? Optional.empty()
                        : Optional.of(
                                quantity.value().multiply(densityKgM3).divide(KG_PER_TONNE, 3, RoundingMode.HALF_UP));
            default -> Optional.empty();
        };
    }
}
