package dev.krona.urbex.varia;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * A map whose entries expire once they have not been touched for a while, and which never grows past
 * a stated size.
 * <p>
 * Backed by a {@link ConcurrentHashMap}: worldgen runs on the vanilla worker pool, so every one of
 * these caches is read and written from several threads at once. There is deliberately no
 * {@code computeIfAbsent} - see {@link #getOrCompute}.
 *
 * <h2>Why the size bound exists</h2>
 *
 * <p>Every value in these caches is a pure function of the world seed and a chunk coordinate, so
 * dropping one is only ever a recomputation - never a change in what gets generated. That is
 * asserted rather than assumed: {@code runDigestCheckAvoidExpire} clears every cache every 25 chunks
 * and reproduces the same golden. What was missing was a ceiling. A player walking in one direction
 * visits new coordinates indefinitely, and a TTL only reclaims what nothing touches - so the maps
 * grew with distance travelled and were bounded by nothing at all (issue #132).</p>
 */
public class TimedCache<K, V> {

    /**
     * How many entries to look at when making room, and how many of those to drop.
     *
     * <p>Sampled rather than exhaustive, so an insert that finds the cache full does a fixed amount
     * of work instead of scanning it. The sample is taken from {@link ConcurrentHashMap}'s iteration
     * order, which is by hash bucket and so unrelated to both insertion order and coordinate - it is
     * effectively a random draw, and the oldest of a random 32 is old enough. What matters here is
     * the ceiling, not which particular entry pays for it.</p>
     */
    private static final int EVICTION_SAMPLE = 32;
    private static final int EVICTION_TAKE = 8;

    /**
     * Where this cache's counters go, or null when metrics are off - which is every run that is not
     * measuring. Null rather than a no-op object so the branch below is a null check the JIT can
     * fold, and so an unmeasured run allocates no counters at all (issue #132).
     */
    private final GenerationMetrics.CacheStats stats;

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
    private final IntSupplier maxEntriesSupplier;
    private final AtomicLong nextCleanupAt;

    public TimedCache(IntSupplier ttlSecondsSupplier) {
        this(ttlSecondsSupplier, null);
    }

    /**
     * @param name what this cache is called in a {@code PERF=} report. A cache built without one is
     *             not reported, which is how a cache created inside a test stays out of the numbers.
     */
    public TimedCache(IntSupplier ttlSecondsSupplier, String name) {
        this(ttlSecondsSupplier, dev.krona.urbex.setup.Config::cacheMaxEntries, name);
    }

    public TimedCache(IntSupplier ttlSecondsSupplier, IntSupplier maxEntriesSupplier, String name) {
        this.ttlSecondsSupplier = ttlSecondsSupplier;
        this.maxEntriesSupplier = maxEntriesSupplier;
        this.nextCleanupAt = new AtomicLong(now());
        this.stats = GenerationMetrics.enabled() && name != null
                ? GenerationMetrics.cache(name, cache::size) : null;
    }

    /**
     * Milliseconds on a clock that only moves forward.
     *
     * <p>{@link System#currentTimeMillis()} is wall-clock: an NTP correction or a manual change
     * steps it, and every entry in every cache then looks either freshly touched or long dead
     * depending on which way it moved. Expiry is a duration, so it should be measured with the clock
     * that measures durations. The absolute value is meaningless and only ever subtracted.</p>
     */
    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    public void clear() {
        cache.clear();
    }

    /** How many entries are held right now. O(n) on a ConcurrentHashMap, so: reporting only. */
    public int size() {
        return cache.size();
    }

    public V get(K key) {
        long now = now();
        Entry<V> entry = cache.get(key);
        if (entry == null) {
            if (stats != null) {
                stats.miss();
            }
            maybeCleanup(now);
            return null;
        }
        if (isExpired(entry, now)) {
            cache.remove(key, entry);
            if (stats != null) {
                stats.miss();
                stats.evicted(1);
            }
            maybeCleanup(now);
            return null;
        }
        entry.lastAccess = now;
        if (stats != null) {
            stats.hit();
        }
        maybeCleanup(now);
        return entry.value;
    }

    public void put(K key, V value) {
        long now = now();
        makeRoom();
        cache.put(key, new Entry<>(value, now));
        maybeCleanup(now);
    }

    /**
     * Get, or compute and store. Deliberately not {@link ConcurrentHashMap#computeIfAbsent}: the
     * city caches are mutually recursive (a chunk's info reads its neighbours' candidate,
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
        if (raced != null && stats != null) {
            // Two threads computed the same pure value and one result is dropped. Correct, and
            // documented above; also duplicated work nothing counted until #132.
            stats.raced();
        }
        return raced != null ? raced : computed;
    }

    /**
     * Store only if nothing is there yet. Returns the value that is in the cache after the call if
     * it was already occupied, or null if this call is the one that stored.
     */
    public V putIfAbsent(K key, V value) {
        long now = now();
        makeRoom();
        Entry<V> raced = cache.putIfAbsent(key, new Entry<>(value, now));
        maybeCleanup(now);
        return raced == null ? null : raced.value;
    }

    /**
     * Drops a few entries if the cache is at its ceiling, so the insert about to happen does not
     * push it past.
     *
     * <p>Bounded work, unconditionally: at most {@link #EVICTION_SAMPLE} entries are examined and at
     * most {@link #EVICTION_TAKE} removed, however far over the limit the map is. A cache that is
     * over its ceiling therefore converges towards it over the following inserts rather than paying
     * for the whole correction on whichever worldgen worker happened to notice - which is the
     * "full sweeps do not stall generation workers" half of issue #132.</p>
     */
    private void makeRoom() {
        int max = maxEntriesSupplier.getAsInt();
        if (max <= 0 || cache.size() < max) {
            return;
        }
        // The oldest few of a sample, rather than the oldest overall: finding the true oldest means
        // reading every entry, which is the scan this is here to avoid.
        Object[] oldestKeys = new Object[EVICTION_TAKE];
        long[] oldestTimes = new long[EVICTION_TAKE];
        java.util.Arrays.fill(oldestTimes, Long.MAX_VALUE);
        int seen = 0;
        for (Map.Entry<K, Entry<V>> entry : cache.entrySet()) {
            long age = entry.getValue().lastAccess;
            for (int i = 0; i < EVICTION_TAKE; i++) {
                if (age < oldestTimes[i]) {
                    System.arraycopy(oldestTimes, i, oldestTimes, i + 1, EVICTION_TAKE - i - 1);
                    System.arraycopy(oldestKeys, i, oldestKeys, i + 1, EVICTION_TAKE - i - 1);
                    oldestTimes[i] = age;
                    oldestKeys[i] = entry.getKey();
                    break;
                }
            }
            if (++seen >= EVICTION_SAMPLE) {
                break;
            }
        }
        long removed = 0;
        for (Object key : oldestKeys) {
            if (key != null && cache.remove(key) != null) {
                removed++;
            }
        }
        if (stats != null && removed > 0) {
            stats.evicted(removed);
        }
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

    /**
     * The TTL sweep. Still a full pass, and that is now a bounded statement rather than an open one:
     * {@link #makeRoom} keeps the map at or below the configured ceiling, so this walks at most that
     * many entries - a number an operator chose - instead of however many coordinates a player
     * happened to visit.
     */
    private void cleanup(long now) {
        long started = stats == null ? 0 : System.nanoTime();
        long removed = 0;
        long ttlMillis = getTtlMillis();
        if (ttlMillis <= 0) {
            removed = cache.size();
            cache.clear();
        } else {
            Iterator<Map.Entry<K, Entry<V>>> iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<K, Entry<V>> entry = iterator.next();
                if (now - entry.getValue().lastAccess >= ttlMillis) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        if (stats != null) {
            stats.evicted(removed);
            stats.swept(System.nanoTime() - started, cache.size());
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
