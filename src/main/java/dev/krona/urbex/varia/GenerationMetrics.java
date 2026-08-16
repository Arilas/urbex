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

    /**
     * {@code -Durbex.metrics.warmup=N} discards the first {@code N} chunks a run generates.
     *
     * <p>Nothing here is measured from a cold JVM by choice. The asset compile is already outside
     * the window - {@code AssetCompiler.compile} runs at level load and {@link #reset} runs when the
     * drive starts - but two things still warm up inside it: the palette cache, whose misses are
     * almost entirely in the first few hundred chunks, and the JIT, which over a three-minute run
     * has compiled the generation path long before the run ends. Both inflate the early chunks and
     * neither is a property of the steady state.</p>
     *
     * <p>Zero by default, so an unasked run measures exactly what it measured before. The point is
     * to run the same drive twice and compare, rather than to pick one number and defend it: if the
     * two lines agree, warm-up was not distorting anything and the exclusion can be forgotten.</p>
     */
    public static final String WARMUP_PROPERTY = "urbex.metrics.warmup";

    private static final boolean ENABLED = System.getProperty(ENABLED_PROPERTY) != null;

    private static final int WARMUP_CHUNKS = Math.max(0, Integer.getInteger(WARMUP_PROPERTY, 0));

    private static final com.sun.management.ThreadMXBean THREADS = threadBean();

    /**
     * Which half of a chunk's generation a sample belongs to.
     *
     * <p>The split is the one {@code CityGenerator.generateOrThrow} already makes and that every
     * proposal to move work off the chunk pipeline's critical path depends on. {@link #PLAN} is a
     * pure function of the seed and the coordinate - {@code TerrainSampler} reads no block, and
     * {@code DimensionCaches} documents every value in it as recomputable by any thread at any time
     * - so it is the half that <em>could</em> be computed before anyone asks for the chunk.
     * {@link #BUILD} needs the real chunk and the real region, so it cannot.</p>
     *
     * <p>Measuring them separately is what turns "planning looks expensive" into a ratio.
     * {@link #PLAN} and {@link #BUILD} are contiguous and exhaustive: every nanosecond
     * {@link #chunk} counts is in exactly one of them, which is why the report can print each as a
     * percentage of the chunk mean.</p>
     *
     * <p>Every phase after {@code BUILD} is one pass <em>inside</em> it, so those overlap {@code
     * BUILD} rather than partitioning the chunk - they sum to roughly what {@code BUILD} reports,
     * and the shortfall is the glue between passes. Each is a share of the same chunk mean, which
     * is the comparison that matters: a pass owning a visible fraction of a chunk is worth opening
     * up, and one owning 0.3% is not, whatever its internals look like.</p>
     */
    public enum Phase {
        /** The heightmap copy and the chunk plan: pure, cached, speculatable. */
        PLAN,
        /** Everything after it, as one figure. The sub-phases below partition this. */
        BUILD,
        /** The village/structure blacklist probe, and the floating-profile void test. */
        PROBE,
        /** {@code doCityChunk}: streets, buildings, parts. The city itself. */
        CITY,
        /** {@code doNormalChunk}: the terrain a non-city chunk gets instead. */
        TERRAIN,
        /** Railways, their dungeons, and the building-collision test that can cancel them. */
        RAIL,
        /** The deferred optional lights, planned and placed. */
        LIGHTS,
        /** Explosion damage and the floating-block repair that follows it. */
        DAMAGE,
        /** Scattered debris. */
        DEBRIS,
        /** {@code driver.actuallyGenerate}: the buffered blocks reaching the chunk. */
        COMMIT,
        /** Inside {@code COMMIT}: the connection/shape corrections pass over every written position. */
        CORRECT,
        /** Inside {@code CORRECT}: extracting and sorting the written positions. */
        CORRECT_SORT,
        /** Inside {@code CORRECT}: the shape/connection resolution over those positions. */
        CORRECT_SHAPE,
        /** Inside {@code COMMIT}: the buffered sections being flushed into the chunk. */
        FLUSH,
        /** Inside {@code COMMIT}: {@code Heightmap.primeHeightmaps} over four heightmap types. */
        HEIGHTMAP,
        /**
         * Inside {@code COMMIT}: handing this chunk's write log to the digest harness.
         *
         * <p><strong>Harness overhead, not generation.</strong> It does nothing unless
         * {@code /urbex digest} switched write recording on - which the digest suites do for every
         * run, including the soak this report comes from. So it is measured precisely so it can be
         * subtracted: a figure taken from a digest run describes the mod as shipped only once this
         * phase is taken out of it.</p>
         */
        PUBLISH,
        /** {@code ChunkFixer.fix} and the block-entity sweep after it. */
        FIXER
    }

    /** Chunk generation times, bucketed by {@code floor(log2(micros))}. */
    private static final LongAdder[] CHUNK_BUCKETS = newBuckets();
    private static final LongAdder CHUNK_COUNT = new LongAdder();
    private static final LongAdder CHUNK_NANOS = new LongAdder();
    private static final AtomicLong CHUNK_MAX_NANOS = new AtomicLong();

    /**
     * How many chunks have <em>started</em> generating, which is what an ordinal is taken from.
     *
     * <p>Counted at the start rather than derived from {@link #CHUNK_COUNT} because a phase is
     * recorded before its chunk finishes, and the warm-up rule has to reach the same verdict for a
     * chunk's phases as it does for the chunk itself. An ordinal handed out once, at the top,
     * settles that for every sample the chunk will produce.</p>
     */
    private static final AtomicLong CHUNK_ORDINAL = new AtomicLong();

    /** How many chunks the warm-up exclusion discarded, so a report can say so rather than imply it. */
    private static final LongAdder WARMUP_SKIPPED = new LongAdder();

    private static final LongAdder[] PHASE_NANOS = newPhaseAdders();
    private static final LongAdder[] PHASE_COUNT = newPhaseAdders();
    private static final LongAdder[] PHASE_ALLOC = newPhaseAdders();
    private static final AtomicLong[] PHASE_MAX_NANOS = newPhaseMaxima();

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
        // The ordinal too, so the warm-up exclusion applies to the first chunks of *this* run. A
        // counter that kept climbing would exclude nothing on a second run in the same JVM, which is
        // the shape the digest suites use.
        CHUNK_ORDINAL.set(0);
        WARMUP_SKIPPED.reset();
        for (int i = 0; i < PHASE_NANOS.length; i++) {
            PHASE_NANOS[i].reset();
            PHASE_COUNT[i].reset();
            PHASE_ALLOC[i].reset();
            PHASE_MAX_NANOS[i].set(0);
        }
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
    public static void chunk(long ordinal, long nanos) {
        if (!ENABLED) {
            return;
        }
        if (isWarmup(ordinal)) {
            WARMUP_SKIPPED.increment();
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
     * Claims this chunk's ordinal, at the top of its generation.
     *
     * <p>Every sample the chunk goes on to produce carries it, so the warm-up rule reaches one
     * verdict per chunk rather than one per sample - a chunk cannot have its planning discarded as
     * warm-up and its building counted. Returns zero, and counts nothing, when metrics are off.</p>
     */
    public static long beginChunk() {
        return ENABLED ? CHUNK_ORDINAL.getAndIncrement() : 0L;
    }

    /** A nanosecond baseline for {@link #phase}, or zero when nobody is measuring. */
    public static long mark() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /**
     * An allocation baseline for {@link #phase}, in bytes, or zero when nobody is measuring.
     *
     * <p>Per thread and cumulative, so the delta across a phase is exactly what that phase
     * allocated on the thread that ran it - which is the only figure worth having here, since
     * worldgen fans out and a heap delta would measure the collector's mood instead. Zero when the
     * JVM is not HotSpot or the counter is off, and a phase whose baseline is zero contributes no
     * allocation rather than a nonsense one.</p>
     */
    public static long allocMark() {
        if (!ENABLED || THREADS == null) {
            return 0L;
        }
        long allocated = THREADS.getCurrentThreadAllocatedBytes();
        // The bean answers -1 when the counter is unavailable for this thread. Normalised to zero so
        // the "no baseline" test below is one condition rather than two.
        return allocated < 0 ? 0L : allocated;
    }

    /**
     * One half of a chunk's generation cost, closing the marks taken before it started.
     *
     * <p>The two phases are contiguous and exhaustive, so their nanoseconds sum to what
     * {@link #chunk} recorded for the same chunk and each can be reported as a share of it. Both
     * are recorded before the chunk is, and all three agree about warm-up because all three are
     * told the same {@code ordinal}.</p>
     *
     * @param ordinal from {@link #beginChunk}, for this chunk
     * @param phase   which half this is
     * @param nanoMark  the {@link #mark} taken when the phase started
     * @param allocMark the {@link #allocMark} taken when the phase started, or zero if there was none
     */
    public static void phase(long ordinal, Phase phase, long nanoMark, long allocMark) {
        if (!ENABLED || isWarmup(ordinal)) {
            return;
        }
        int index = phase.ordinal();
        long nanos = System.nanoTime() - nanoMark;
        PHASE_NANOS[index].add(nanos);
        PHASE_COUNT[index].increment();
        PHASE_MAX_NANOS[index].accumulateAndGet(nanos, Math::max);
        if (allocMark > 0) {
            long allocated = allocMark();
            if (allocated > allocMark) {
                PHASE_ALLOC[index].add(allocated - allocMark);
            }
        }
    }

    /**
     * Whether this chunk is early enough in the run to be discarded.
     *
     * <p>By ordinal rather than by elapsed time: the run is driven chunk by chunk, so "the first N
     * chunks" is the unit warm-up actually happens in, and it is the same N whatever the machine.</p>
     */
    private static boolean isWarmup(long ordinal) {
        return ordinal < WARMUP_CHUNKS;
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
        line.append(" warmup=").append(WARMUP_CHUNKS);
        line.append(" warmupSkipped=").append(WARMUP_SKIPPED.sum());
        for (Phase phase : Phase.values()) {
            line.append(' ').append(phaseReport(phase, totalNanos));
        }
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

    /**
     * One phase's block of the report: its share of the chunk mean, its own mean and tail, and what
     * it allocated per chunk.
     *
     * <p>The share is of {@code totalNanos} - the sum {@link #chunk} recorded - rather than of the
     * two phases added together, so a share that does not add up to 100% is visible rather than
     * normalised away. It should: the phases are contiguous and cover the whole of the generation
     * {@link #chunk} times.</p>
     *
     * <p>Allocation is per chunk rather than a total, because a total only means something next to
     * a chunk count and the whole point of this figure is to be compared with the {@code allocMiB}
     * beside it. In KiB: a phase allocating single-digit MiB per chunk rounds to nothing otherwise,
     * and single-digit MiB per chunk is the interesting case.</p>
     */
    private static String phaseReport(Phase phase, long totalNanos) {
        int index = phase.ordinal();
        long count = PHASE_COUNT[index].sum();
        long nanos = PHASE_NANOS[index].sum();
        long alloc = PHASE_ALLOC[index].sum();
        return String.format("%s=%.0f%%(meanUs=%.1f maxUs=%d allocKiB=%d n=%d)",
                phase.name().toLowerCase(java.util.Locale.ROOT),
                totalNanos == 0 ? 0.0 : nanos * 100.0 / totalNanos,
                count == 0 ? 0.0 : nanos / 1000.0 / count,
                PHASE_MAX_NANOS[index].get() / 1000,
                count == 0 ? 0L : (alloc / count) >> 10,
                count);
    }

    private static LongAdder[] newPhaseAdders() {
        LongAdder[] adders = new LongAdder[Phase.values().length];
        for (int i = 0; i < adders.length; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    private static AtomicLong[] newPhaseMaxima() {
        AtomicLong[] maxima = new AtomicLong[Phase.values().length];
        for (int i = 0; i < maxima.length; i++) {
            maxima[i] = new AtomicLong();
        }
        return maxima;
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
