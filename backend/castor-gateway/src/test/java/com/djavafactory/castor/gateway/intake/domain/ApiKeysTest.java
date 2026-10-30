package com.djavafactory.castor.gateway.intake.domain;

import java.util.Base64;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeysTest {

    @Test
    void aKeyIs32RandomBytesInUrlSafeBase64() {
        String key = ApiKeys.generate(new Random(1));

        assertTrue(key.startsWith("ck_"));
        assertEquals(32, Base64.getUrlDecoder().decode(key.substring(3)).length);
        assertFalse(key.contains("=") || key.contains("+") || key.contains("/"));
    }

    @Test
    void keysDiffer() {
        var random = new Random(2);

        assertNotEquals(ApiKeys.generate(random), ApiKeys.generate(random));
    }

    @Test
    void theHashIsSha256OfTheKeyText() {
        // SHA-256("abc")
        byte[] expected =
                java.util.HexFormat.of().parseHex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        assertArrayEquals(expected, ApiKeys.hash("abc"));
        assertEquals(32, ApiKeys.hash("a much longer key than the other one").length);
    }

    @Test
    void thePrefixIsTheFirstEightCharacters() {
        assertEquals("ck_AbCdE", ApiKeys.prefix("ck_AbCdEfGhIj"));
        assertEquals("short", ApiKeys.prefix("short"));
    }

    @Test
    void sourcesAreFoundByTheirLowerCaseCode() {
        for (Source source : Source.values()) {
            assertEquals(source, Source.fromCode(source.code()).orElseThrow());
        }
        assertTrue(Source.fromCode("CANONICAL").isEmpty());
        assertTrue(Source.fromCode("unknown").isEmpty());
    }
}
