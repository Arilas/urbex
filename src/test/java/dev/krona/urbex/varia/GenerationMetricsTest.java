package dev.krona.urbex.varia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The measurement is off unless asked for, and says so rather than reporting zeroes.
 * <p>
 * These run without {@code -Durbex.metrics}, which is the state every non-measuring run is in - so
 * what they pin is the off path. A counter that is always on is a measurement that changes what it
 * measures, and for a per-chunk path that is not a theoretical concern (issue #132).
 */
class GenerationMetricsTest {

    @Test
    void metricsAreOffUnlessTheSystemPropertyIsSet() {
        assertFalse(GenerationMetrics.enabled(),
                "the test JVM sets no -Durbex.metrics, so this is the shipped default");
    }

    @Test
    void anUnaskedReportSaysSoRatherThanReportingZeroes() {
        String report = GenerationMetrics.report(625, 24000);

        assertTrue(report.startsWith("PERF=off"), report);
        assertTrue(report.contains(GenerationMetrics.ENABLED_PROPERTY),
                "and says how to ask: " + report);
    }

    @Test
    void recordingWhileOffCostsNothingAndChangesNothing() {
        long ordinal = GenerationMetrics.beginChunk();
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.PLAN, 0L, 0L);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.BUILD, 0L, 0L);
        GenerationMetrics.chunk(ordinal, 5_000_000L);
        GenerationMetrics.queueDepth(4096);

        assertEquals(0, GenerationMetrics.percentileMicros(99),
                "a chunk recorded while off must not appear in a later measured run");
        assertEquals(0, GenerationMetrics.allocatedBytes());
    }

    /**
     * The marks are the two values a phase is closed against, and off they must be free. Zero is
     * also what {@code phase} reads as "there was no baseline", so an off run records no allocation
     * rather than a delta against a clock it never started.
     */
    @Test
    void theMarksAnswerZeroWhileOffRatherThanReadingAClock() {
        assertEquals(0, GenerationMetrics.mark());
        assertEquals(0, GenerationMetrics.allocMark());
        assertEquals(0, GenerationMetrics.beginChunk(),
                "no ordinal is handed out when nothing is counting them");
    }

    /**
     * Both phases appear in every measured report, including one where nothing generated. A phase
     * that only appeared once it had samples would make "planning cost nothing" and "planning was
     * not measured" the same line.
     */
    @Test
    void anUnaskedReportNamesNoPhasesBecauseItReportsNothingAtAll() {
        String report = GenerationMetrics.report(625, 24000);

        assertFalse(report.contains("plan="), report);
        assertFalse(report.contains("build="), report);
    }

    /**
     * Warm-up exclusion is off by default, so an unasked run measures what it always measured. The
     * property exists to be set for one comparison run and then compared against a run without it.
     */
    @Test
    void warmupExclusionIsOffUnlessAskedFor() {
        assertEquals(null, System.getProperty(GenerationMetrics.WARMUP_PROPERTY),
                "the test JVM sets no warm-up, so this is the shipped default");
    }

    /**
     * The percentile is bucketed by powers of two, deliberately: an exact one needs every sample
     * kept, and a million longs is itself the allocation problem being measured. What matters is
     * that a bucket boundary is where it claims to be.
     */
    @Test
    void percentilesAreEmptyWithNoSamples() {
        GenerationMetrics.reset();

        assertEquals(0, GenerationMetrics.percentileMicros(50));
        assertEquals(0, GenerationMetrics.percentileMicros(99));
    }

    @Test
    void aCacheWithNoLookupsReportsNoHitRateRatherThanDividingByZero() {
        GenerationMetrics.CacheStats stats = GenerationMetrics.cache("test-empty", () -> 0);

        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
        assertEquals(0, stats.races());
    }

    @Test
    void cacheCountersAccumulateIndependentlyOfWhetherReportingIsOn() {
        // TimedCache only wires these up when metrics are on, but the counters themselves are just
        // adders - a test that could not touch them without a system property would be a test of
        // the property rather than of the counting.
        GenerationMetrics.CacheStats stats = GenerationMetrics.cache("test-counting", () -> 3);
        stats.hit();
        stats.hit();
        stats.miss();
        stats.raced();
        stats.evicted(7);

        assertEquals(2, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(1, stats.races());
        assertEquals(7, stats.evictions());
    }
}
