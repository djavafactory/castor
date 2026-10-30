package com.djavafactory.castor.gateway.shared.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A small cache whose entries expire after a fixed time on the injected clock (rule R8), so a stepped test clock decides
 * when a value is read again. A value is loaded outside any lock: two threads that miss together both load, and the
 * last one stays.
 */
public final class TtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {}

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final InstantSource clock;
    private final Duration ttl;
    private final int maxEntries;

    public TtlCache(InstantSource clock, Duration ttl, int maxEntries) {
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    public V get(K key, Function<K, V> loader) {
        Instant now = clock.instant();
        Entry<V> entry = entries.get(key);
        if (entry != null && now.isBefore(entry.expiresAt())) {
            return entry.value();
        }
        V value = loader.apply(key);
        if (entries.size() >= maxEntries) {
            entries.clear();
        }
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        return value;
    }

    public void clear() {
        entries.clear();
    }
}
