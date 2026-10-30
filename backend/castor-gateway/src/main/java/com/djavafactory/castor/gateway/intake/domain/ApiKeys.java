package com.djavafactory.castor.gateway.intake.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.random.RandomGenerator;

/**
 * API keys are 32 random bytes written as {@code ck_} and URL-safe Base64. Only the SHA-256 hash of the key text is
 * stored, together with its first characters as a label; the key itself is shown once, when it is created. The hash is
 * enough to check a key because the key is random, not chosen by a person.
 */
public final class ApiKeys {

    static final String MARK = "ck_";
    static final int PREFIX_LENGTH = 8;

    private ApiKeys() {}

    /** A new key; give it a {@code SecureRandom}. */
    public static String generate(RandomGenerator random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return MARK + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the key text (UTF-8): what ingest.api_key.key_hash holds. */
    public static byte[] hash(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** The label kept next to the hash so a key can be recognised without being known. */
    public static String prefix(String key) {
        return key.substring(0, Math.min(PREFIX_LENGTH, key.length()));
    }
}
