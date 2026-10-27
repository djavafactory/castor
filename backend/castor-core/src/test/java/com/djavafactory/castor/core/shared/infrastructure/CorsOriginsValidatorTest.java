package com.djavafactory.castor.core.shared.infrastructure;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorsOriginsValidatorTest {

    @Test
    void acceptsExplicitOrigins() {
        assertDoesNotThrow(
                () -> CorsOriginsValidator.validate(List.of("https://castor.replica8.it", "http://localhost:5173")));
    }

    @Test
    void rejectsWildcard() {
        assertThrows(IllegalStateException.class, () -> CorsOriginsValidator.validate(List.of("*")));
    }

    @Test
    void rejectsEmptyList() {
        assertThrows(IllegalStateException.class, () -> CorsOriginsValidator.validate(List.of()));
    }
}
