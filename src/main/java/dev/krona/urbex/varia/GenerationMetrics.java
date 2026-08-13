package dev.krona.urbex.varia;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * What one generation run cost, when anyone is asking.
 *
 * <p>Epic #134 forbids optimizing a deterministic generation path from inspection alone, so several
 * phase-4 changes have to attach numbers. These are those numbers, and they are collected on the
 * digest run rather than in a harness of their own: throughput, allocation, cache behaviour and tail
 * latency are all properties of generating a known square of chunks, which is a thing the digest
 * suites already do repeatably (issue #132).</p>
 *
 * <p><strong>Off unless asked.</strong> {@code -Durbex.metrics} switches it on. Off, every entry
 * point below is a static-final {@code false} and a branch the JIT folds away - a counter that is
 * always on is a measurement that changes what it measures, which for a per-block path is not a
 * theoretical concern.</p>
 *
 * <h2>Why allocation is per thread</h2>
 *
 * <p>Worldgen fans out over the worker pool, so a figure taken from {@code Runtime.totalMemory()}
 * deltas measures the collector's mood rather than the run. {@code ThreadMXBean
 * .getThreadAllocatedBytes} is cumulative and exact per thread, so a run's allocation is the sum of
 * the deltas of every thread that generated anything - which is why {@link #chunk} samples it: the
 * threads that generate are exactly the threads that call it.</p>
 */
public final class GenerationMetrics {

    /** {@code -Durbex.metrics} to switch the counters on. */
    public static final String ENABLED_PROPERTY = "urbex.metrics";

    private static final boolean ENABLED = System.getProperty(ENABLED_PROPERTY) != null;

    private static final com.sun.management.ThreadMXBean THREADS = threadBean();

    /** Chunk generation times, bucketed by {@code floor(log2(micros))}. */
    private static final LongAdder[] CHUNK_BUCKETS = newBuckets();
    private static final LongAdder CHUNK_COUNT = new LongAdder();
    private static final LongAdder CHUNK_NANOS = new LongAdder();
    private static final AtomicLong CHUNK_MAX_NANOS = new AtomicLong();

    /** Thread id -> allocated bytes when that thread first generated something. */
    private static final Map<Long, Long> ALLOCATION_BASELINE = new ConcurrentHashMap<>();
    private static final Map<Long, Long> ALLOCATION_LATEST = new ConcurrentHashMap<>();

    private static final Map<String, CacheStats> CACHES = new ConcurrentHashMap<>();

    private static final AtomicLong QUEUE_HIGH_WATER = new AtomicLong();

    private GenerationMetrics() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Forgets everything measured so far. Called at the start of a measured run. */
    public static void reset() {
        for (LongAdder bucket : CHUNK_BUCKETS) {
            bucket.reset();
        }
        CHUNK_COUNT.reset();
        CHUNK_NANOS.reset();
        CHUNK_MAX_NANOS.set(0);
        ALLOCATION_BASELINE.clear();
        ALLOCATION_LATEST.clear();
        // Zeroed in place, not dropped: a TimedCache takes its CacheStats once, at construction, so
        // clearing the map would leave every existing cache writing to an object nothing reports.
        CACHES.values().forEach(CacheStats::reset);
        QUEUE_HIGH_WATER.set(0);
    }

    /**
     * One generated chunk took {@code nanos}.
     *
     * <p>Also samples this thread's allocation counter. The first call from a thread records its
     * baseline and contributes nothing; every later one moves that thread's latest, so a run's
     * allocation is the sum over threads of {@code latest - baseline}. Sampling here rather than
     * around the whole run is what keeps it to the threads that actually generated.</p>
     */
    public static void chunk(long nanos) {
        if (!ENABLED) {
            return;
        }
        CHUNK_COUNT.increment();
        CHUNK_NANOS.add(nanos);
        CHUNK_MAX_NANOS.accumulateAndGet(nanos, Math::max);
        CHUNK_BUCKETS[bucketFor(nanos)].increment();
        if (THREADS != null) {
            long id = Thread.currentThread().threadId();
            long allocated = THREADS.getThreadAllocatedBytes(id);
            if (allocated >= 0) {
                ALLOCATION_BASELINE.putIfAbsent(id, allocated);
                ALLOCATION_LATEST.put(id, allocated);
            }
        }
    }

    /**
     * The counters for a named cache, created on first use.
     *
     * @param liveSize how many entries the cache holds right now, sampled when a report is built.
     *                 Sampled rather than tracked because the alternative is maintaining a count on
     *                 the put/remove path of a concurrent map, which is a cost the measurement would
     *                 then be measuring.
     */
    public static CacheStats cache(String name, LongSupplier liveSize) {
        CacheStats stats = CACHES.computeIfAbsent(name, n -> new CacheStats());
        stats.liveSize = liveSize;
        return stats;
    }

    /** A deferred-work queue reached {@code depth} entries. */
    public static void queueDepth(long depth) {
        if (!ENABLED) {
            return;
        }
        QUEUE_HIGH_WATER.accumulateAndGet(depth, Math::max);
    }

    /**
     * One line: throughput, allocation, tail latency, then a block per cache.
     *
     * @param chunks how many chunks the run asked for, which is not the same as how many generated
     *               city content
     * @param millis wall-clock time for the drive
     */
    public static String report(int chunks, long millis) {
        if (!ENABLED) {
            return "PERF=off (pass -D" + ENABLED_PROPERTY + " to measure)";
        }
        long generated = CHUNK_COUNT.sum();
        long totalNanos = CHUNK_NANOS.sum();
        StringBuilder line = new StringBuilder("PERF=on");
        line.append(" chunks=").append(chunks);
        line.append(" generated=").append(generated);
        line.append(" ms=").append(millis);
        line.append(String.format(" chunksPerSec=%.1f", millis == 0 ? 0.0 : generated * 1000.0 / millis));
        line.append(String.format(" meanUs=%.1f", generated == 0 ? 0.0 : totalNanos / 1000.0 / generated));
        line.append(String.format(" p99Us=%d", percentileMicros(99)));
        line.append(String.format(" maxUs=%d", CHUNK_MAX_NANOS.get() / 1000));
        line.append(" allocMiB=").append(allocatedBytes() >> 20);
        line.append(" allocThreads=").append(ALLOCATION_BASELINE.size());
        line.append(" queueHighWater=").append(QUEUE_HIGH_WATER.get());
        CACHES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> line.append(' ').append(entry.getKey()).append('=')
                        .append(entry.getValue().format()));
        return line.toString();
    }

    /** Total bytes allocated by every thread that generated a chunk during this run. */
    public static long allocatedBytes() {
        long total = 0;
        for (Map.Entry<Long, Long> entry : ALLOCATION_LATEST.entrySet()) {
            Long baseline = ALLOCATION_BASELINE.get(entry.getKey());
            if (baseline != null) {
                total += entry.getValue() - baseline;
            }
        }
        return total;
    }

    /**
     * The {@code p}th percentile chunk time, to within its bucket.
     * <p>
     * Log-bucketed rather than exact: an exact percentile needs every sample kept, and a million
     * longs is itself the allocation problem this is measuring. A bucket is a factor of two, which
     * is enough to see a tail move and not enough to argue about.
     */
    public static long percentileMicros(int p) {
        long total = CHUNK_COUNT.sum();
        if (total == 0) {
            return 0;
        }
        long target = total * p / 100;
        long seen = 0;
        for (int i = 0; i < CHUNK_BUCKETS.length; i++) {
            seen += CHUNK_BUCKETS[i].sum();
            if (seen >= target) {
                return 1L << i;
            }
        }
        return 1L << (CHUNK_BUCKETS.length - 1);
    }

    private static int bucketFor(long nanos) {
        long micros = Math.max(1, nanos / 1000);
        int bucket = 63 - Long.numberOfLeadingZeros(micros);
        return Math.min(bucket, CHUNK_BUCKETS.length - 1);
    }

    private static LongAdder[] newBuckets() {
        // 2^24 microseconds is about 17 seconds; anything past that is one chunk, not a distribution.
        LongAdder[] buckets = new LongAdder[25];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
        return buckets;
    }

    private static com.sun.management.ThreadMXBean threadBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean hotspot)
                || !hotspot.isThreadAllocatedMemorySupported()) {
            // Not HotSpot, or the feature is off. Everything else still measures.
            return null;
        }
        hotspot.setThreadAllocatedMemoryEnabled(true);
        return hotspot;
    }

    /**
     * One cache's counters.
     *
     * <p>{@code races} is the one that needed a name. {@code TimedCache.getOrCompute} computes
     * outside the map on purpose - {@code computeIfAbsent} deadlocks on the mutually recursive city
     * caches - so two threads may compute the same pure value and one result is dropped. That is
     * correct and documented, and it is also duplicated work nothing counted.</p>
     *
     * <p>There is no {@code computes} counter, deliberately. Most of these caches are populated
     * through {@code get} then {@code putIfAbsent} rather than {@code getOrCompute}, so a compute
     * count would be zero for them and non-zero for the three that use the other shape - which reads
     * as "these caches never compute anything" rather than as "this counter does not apply here".
     * The miss count is the compute count for every one of them.</p>
     */
    public static final class CacheStats {
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder races = new LongAdder();
        private final LongAdder evictions = new LongAdder();
        private final LongAdder sweeps = new LongAdder();
        private final LongAdder sweepNanos = new LongAdder();
        private final AtomicLong sweptTo = new AtomicLong();
        private volatile LongSupplier liveSize = () -> -1;

        public void hit() {
            hits.increment();
        }

        public void miss() {
            misses.increment();
        }

        public void raced() {
            races.increment();
        }

        public void evicted(long count) {
            evictions.add(count);
        }

        public void swept(long nanos, long remaining) {
            sweeps.increment();
            sweepNanos.add(nanos);
            sweptTo.set(remaining);
        }

        public long hits() {
            return hits.sum();
        }

        public long misses() {
            return misses.sum();
        }

        public long races() {
            return races.sum();
        }

        public long evictions() {
            return evictions.sum();
        }

        public long sweeps() {
            return sweeps.sum();
        }

        void reset() {
            hits.reset();
            misses.reset();
            races.reset();
            evictions.reset();
            sweeps.reset();
            sweepNanos.reset();
            sweptTo.set(0);
        }

        String format() {
            long hit = hits.sum();
            long miss = misses.sum();
            long lookups = hit + miss;
            return String.format("%d/%d(%.0f%%) races=%d evicted=%d sweeps=%d sweepMs=%d size=%d",
                    hit, lookups, lookups == 0 ? 0.0 : hit * 100.0 / lookups,
                    races.sum(), evictions.sum(), sweeps.sum(),
                    sweepNanos.sum() / 1_000_000, liveSize.getAsLong());
        }
    }
}
