package it.replica8.castor.core.layout.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import it.replica8.castor.core.shared.domain.RuleViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LayoutVersionTest {

    @Test
    void versionNumberStartsAtOne() {
        var e = assertThrows(
                RuleViolationException.class, () -> new LayoutVersion(UUID.randomUUID(), UUID.randomUUID(), 0));

        assertEquals("VERSION_NUMBER", e.violations().get(0).code());
    }

    @Test
    void acceptsPositiveNumbers() {
        assertEquals(1, new LayoutVersion(UUID.randomUUID(), UUID.randomUUID(), 1).number());
    }
}
