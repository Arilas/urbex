package dev.krona.urbex.varia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ceiling, and that reaching it costs a bounded amount.
 *
 * <p>Every value in these caches is a pure function of the world seed and a coordinate, so evicting
 * one is a recomputation rather than a change in what gets generated - which is why a ceiling is
 * allowed to exist at all. What it must not do is stall a worldgen worker while it makes room
 * (issue #132).</p>
 */
class TimedCacheTest {

    private static TimedCache<Integer, String> bounded(int max) {
        return new TimedCache<>(() -> 300, () -> max, null);
    }

    @Test
    void aCacheNeverGrowsPastItsCeiling() {
        TimedCache<Integer, String> cache = bounded(256);

        for (int i = 0; i < 10_000; i++) {
            cache.put(i, "v" + i);
        }

        assertTrue(cache.size() <= 256,
                "40x the ceiling was inserted; the map held " + cache.size());
    }

    /**
     * One insert into a cache that is far over its ceiling drops a handful of entries, not however
     * many it takes to get back under. That is the property that keeps a worldgen worker from
     * wearing the whole correction: a cache whose ceiling was lowered - or that was filled before
     * the limit was read - converges over the following inserts instead.
     */
    @Test
    void oneInsertIntoAnOverfullCacheDoesBoundedWork() {
        int[] ceiling = {1_000_000};
        TimedCache<Integer, String> cache = new TimedCache<>(() -> 300, () -> ceiling[0], null);
        for (int i = 0; i < 6_000; i++) {
            cache.put(i, "v" + i);
        }
        assertEquals(6_000, cache.size());

        ceiling[0] = 256;
        cache.put(-1, "one more");

        int dropped = 6_000 - cache.size() + 1;   // +1 for the entry just added
        assertTrue(dropped <= 8,
                "a single insert dropped " + dropped + " entries; it should sample and take a few");
        assertTrue(cache.size() > 5_000,
                "and so the cache is still far over its new ceiling, on purpose: " + cache.size());
    }

    @Test
    void anOverfullCacheConvergesToItsCeiling() {
        TimedCache<Integer, String> cache = bounded(256);

        for (int i = 0; i < 6_000; i++) {
            cache.put(i, "v" + i);
        }

        assertTrue(cache.size() <= 256, "converged to " + cache.size());
    }

    @Test
    void anUnboundedCeilingEvictsNothing() {
        TimedCache<Integer, String> cache = new TimedCache<>(() -> 300, () -> 0, null);

        for (int i = 0; i < 2_000; i++) {
            cache.put(i, "v" + i);
        }

        assertEquals(2_000, cache.size(), "a ceiling of zero means no ceiling");
    }

    @Test
    void aStoredValueIsReadBack() {
        TimedCache<Integer, String> cache = bounded(256);

        cache.put(1, "one");

        assertEquals("one", cache.get(1));
        assertNull(cache.get(2));
    }

    /**
     * A zero TTL means "hold nothing across a cleanup", which is the existing contract and the one
     * the forced-expiry digest run leans on.
     */
    @Test
    void aZeroTtlDropsEverythingOnTheNextSweep() {
        TimedCache<Integer, String> cache = new TimedCache<>(() -> 0, () -> 1024, null);

        cache.put(1, "one");

        assertNull(cache.get(1));
    }

    @Test
    void getOrComputeStoresWhatItComputed() {
        TimedCache<Integer, String> cache = bounded(256);

        assertEquals("computed", cache.getOrCompute(7, k -> "computed"));
        assertNotNull(cache.get(7));
        assertEquals("computed", cache.getOrCompute(7, k -> {
            throw new AssertionError("the second call must not recompute");
        }));
    }

    @Test
    void computingNullStoresNothing() {
        TimedCache<Integer, String> cache = bounded(256);

        assertNull(cache.getOrCompute(7, k -> null));
        assertEquals(0, cache.size());
    }
}
