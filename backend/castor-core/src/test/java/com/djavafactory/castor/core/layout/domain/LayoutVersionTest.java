package com.djavafactory.castor.core.layout.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.shared.domain.RuleViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LayoutVersionTest {

    private static LayoutVersion version(int no) {
        return new LayoutVersion(
                UUID.randomUUID(),
                "westports",
                no,
                LayoutStatus.DRAFT,
                null,
                "seed",
                Instant.now(),
                null,
                null,
                null,
                null,
                "seed",
                Instant.now(),
                0);
    }

    @Test
    void versionNumberStartsAtOne() {
        var e = assertThrows(RuleViolationException.class, () -> version(0));

        assertEquals("VERSION_NUMBER", e.violations().get(0).code());
    }

    @Test
    void acceptsPositiveNumbers() {
        assertEquals(1, version(1).versionNo());
    }
}
