package com.djavafactory.castor.core.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;

/**
 * What a tank or silo sensor last read, kept next to the book balance and never mixed into it. Density and temperature
 * are the ones the event carried; null when it carried none.
 */
public record ObservedLevel(
        Quantity quantity,
        Instant observedAt,
        String source,
        Quality quality,
        BigDecimal densityKgM3,
        BigDecimal temperatureC) {}
