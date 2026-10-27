package com.djavafactory.castor.core.shared.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedDomainTest {

    @Test
    void ruleViolationRequiresCodeAndMessage() {
        assertThrows(NullPointerException.class, () -> new RuleViolation(null, null, null, "m"));
        assertThrows(NullPointerException.class, () -> new RuleViolation("C", null, null, null));
        var v = new RuleViolation("C", null, "/p", "m");
        assertNull(v.assetId());
        assertEquals("/p", v.pointer());
    }

    @Test
    void ruleViolationExceptionKeepsTitleAndAnImmutableCopy() {
        var source = new java.util.ArrayList<>(List.of(new RuleViolation("C", "a1", null, "m")));
        var e = new RuleViolationException("Title", source);
        source.clear();

        assertEquals("Title", e.title());
        assertEquals("Title", e.getMessage());
        assertEquals(1, e.violations().size());
        assertThrows(UnsupportedOperationException.class, () -> e.violations().clear());
    }

    @Test
    void conflictAndNotFoundCarryTheirMessage() {
        assertEquals("x", new ConflictException("x").getMessage());
        assertEquals("y", new ResourceNotFoundException("y").getMessage());
    }

    @Test
    void draftAlreadyExistsIsAConflict() {
        assertInstanceOf(ConflictException.class, new DraftAlreadyExistsException());
    }

    @Test
    void missingIfMatchNamesTheHeader() {
        assertEquals("The If-Match header is required", new MissingIfMatchException().getMessage());
    }
}
