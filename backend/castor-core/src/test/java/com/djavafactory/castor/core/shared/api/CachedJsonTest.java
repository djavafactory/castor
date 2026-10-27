package com.djavafactory.castor.core.shared.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedJsonTest {

    @Test
    void matchesStrongWeakListAndWildcard() {
        assertTrue(CachedJson.matches("\"abc\"", "abc"));
        assertTrue(CachedJson.matches("W/\"abc\"", "abc"));
        assertTrue(CachedJson.matches("\"x\", \"abc\"", "abc"));
        assertTrue(CachedJson.matches("*", "abc"));
    }

    @Test
    void doesNotMatchOtherTagsOrMissingHeader() {
        assertFalse(CachedJson.matches("\"other\"", "abc"));
        assertFalse(CachedJson.matches("abc", "abc"));
        assertFalse(CachedJson.matches(null, "abc"));
        assertFalse(CachedJson.matches(" ", "abc"));
    }
}
