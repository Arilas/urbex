package dev.krona.urbex.varia;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * A map whose entries expire once they have not been touched for a while.
 * <p>
 * Backed by a {@link ConcurrentHashMap}: worldgen runs on the vanilla worker pool, so every one of
 * these caches is read and written from several threads at once. There is deliberately no
 * {@code computeIfAbsent} - see {@link #getOrCompute}.
 */
public class TimedCache<K, V> {

    private static class Entry<V> {
        private final V value;
        // Written on every read, from whichever thread did the reading. Volatile so a later
        // expiry check on another thread cannot see a stale timestamp and drop a live entry.
        private volatile long lastAccess;

        private Entry(V value, long lastAccess) {
            this.value = value;
            this.lastAccess = lastAccess;
        }
    }

    private final Map<K, Entry<V>> cache = new ConcurrentHashMap<>();
    private final IntSupplier ttlSecondsSupplier;
    private final AtomicLong nextCleanupAt;

    public TimedCache(IntSupplier ttlSecondsSupplier) {
        this.ttlSecondsSupplier = ttlSecondsSupplier;
        this.nextCleanupAt = new AtomicLong(System.currentTimeMillis());
    }

    public void clear() {
        cache.clear();
    }

    public V get(K key) {
        long now = System.currentTimeMillis();
        Entry<V> entry = cache.get(key);
        if (entry == null) {
            maybeCleanup(now);
            return null;
        }
        if (isExpired(entry, now)) {
            cache.remove(key, entry);
            maybeCleanup(now);
            return null;
        }
        entry.lastAccess = now;
        maybeCleanup(now);
        return entry.value;
    }

    public void put(K key, V value) {
        long now = System.currentTimeMillis();
        cache.put(key, new Entry<>(value, now));
        maybeCleanup(now);
    }

    /**
     * Get, or compute and store. Deliberately not {@link ConcurrentHashMap#computeIfAbsent}: the
     * city caches are mutually recursive (a chunk's info reads its neighbours' characteristics,
     * which read their city styles), and computeIfAbsent deadlocks on recursive population - even
     * for distinct keys that land in the same bin. Computing outside the map means two threads may
     * race and both compute; that is harmless, because the computation is a pure function of the
     * world seed.
     */
    public V getOrCompute(K key, Function<K, V> supplier) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        V computed = supplier.apply(key);
        if (computed == null) {
            return null;
        }
        V raced = putIfAbsent(key, computed);
        return raced != null ? raced : computed;
    }

    /**
     * Store only if nothing is there yet. Returns the value that is in the cache after the call if
     * it was already occupied, or null if this call is the one that stored.
     */
    public V putIfAbsent(K key, V value) {
        long now = System.currentTimeMillis();
        Entry<V> raced = cache.putIfAbsent(key, new Entry<>(value, now));
        maybeCleanup(now);
        return raced == null ? null : raced.value;
    }

    private boolean isExpired(Entry<V> entry, long now) {
        return now - entry.lastAccess >= getTtlMillis();
    }

    private void maybeCleanup(long now) {
        long due = nextCleanupAt.get();
        if (now < due) {
            return;
        }
        // Exactly one thread runs the sweep; the others carry on with their lookup.
        if (!nextCleanupAt.compareAndSet(due, now + getCleanupIntervalMillis())) {
            return;
        }
        cleanup(now);
    }

    private void cleanup(long now) {
        long ttlMillis = getTtlMillis();
        if (ttlMillis <= 0) {
            cache.clear();
            return;
        }
        Iterator<Map.Entry<K, Entry<V>>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> entry = iterator.next();
            if (now - entry.getValue().lastAccess >= ttlMillis) {
                iterator.remove();
            }
        }
    }

    private long getCleanupIntervalMillis() {
        long ttlMillis = getTtlMillis();
        return Math.max(1000L, ttlMillis / 2);
    }

    private long getTtlMillis() {
        int ttlSeconds = ttlSecondsSupplier.getAsInt();
        if (ttlSeconds <= 0) {
            return 0L;
        }
        return ttlSeconds * 1000L;
    }
}
