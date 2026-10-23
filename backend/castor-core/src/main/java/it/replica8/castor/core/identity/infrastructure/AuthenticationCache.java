package it.replica8.castor.core.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.security.identity.SecurityIdentity;

import it.replica8.castor.core.shared.infrastructure.CastorConfig;

/**
 * Remembers verified logins for a few seconds so bcrypt does not run on every request. The key holds a salted SHA-256
 * of the password, never the password. Never longer than 60 seconds; a wrong password for the user evicts its entries.
 */
@ApplicationScoped
public class AuthenticationCache {

    static final Duration MAX_TTL = Duration.ofSeconds(60);
    private static final int PRUNE_ABOVE = 1_000;

    private record Entry(SecurityIdentity identity, long expiresAtNanos) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final byte[] salt = new byte[16];
    private final long ttlNanos;

    public AuthenticationCache(CastorConfig castor) {
        new SecureRandom().nextBytes(salt);
        Duration ttl = castor.security().authCacheTtl();
        this.ttlNanos = ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL.toNanos() : Math.max(0, ttl.toNanos());
    }

    SecurityIdentity get(String username, String password) {
        if (ttlNanos == 0) {
            return null;
        }
        Entry entry = entries.get(key(username, password));
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos() >= 0) {
            entries.remove(key(username, password));
            return null;
        }
        return entry.identity();
    }

    void put(String username, String password, SecurityIdentity identity) {
        if (ttlNanos == 0) {
            return;
        }
        if (entries.size() > PRUNE_ABOVE) {
            long now = System.nanoTime();
            entries.values().removeIf(e -> now - e.expiresAtNanos() >= 0);
        }
        entries.put(key(username, password), new Entry(identity, System.nanoTime() + ttlNanos));
    }

    void evict(String username) {
        String prefix = username.toLowerCase() + ":";
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private String key(String username, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            return username.toLowerCase() + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
