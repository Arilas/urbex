package dev.krona.urbex.worldgen;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One chunk, one generation, whichever route asks.
 * <p>
 * Two entry points reach city generation - the carver-tail hook and the registered
 * {@code urbex:city} feature - and until issue #131 nothing coordinated them: a datapack that named
 * the feature in a biome's {@code features} generated the chunk twice, the second pass planning
 * against terrain the first pass had already rewritten. The claim below is what makes them mutually
 * exclusive.
 * <p>
 * Driven through {@link GeneratedChunkMark#claimMark} rather than the {@code ChunkAccess} overload:
 * {@code ChunkAccess} is an abstract class, so it can be neither constructed nor proxied here, and
 * the rule under test is about the mark rather than about chunks.
 */
class GeneratedChunkMarkTest {

    @Test
    void theFirstCallerClaimsTheChunkAndTheSecondIsRefused() {
        Object chunk = new Marked();

        assertTrue(GeneratedChunkMark.claimMark(chunk), "the first route to arrive generates");
        assertFalse(GeneratedChunkMark.claimMark(chunk), "the second does not");
        assertFalse(GeneratedChunkMark.claimMark(chunk), "and neither does a third");
    }

    @Test
    void chunksAreClaimedIndependently() {
        Object one = new Marked();
        Object other = new Marked();

        assertTrue(GeneratedChunkMark.claimMark(one));
        assertTrue(GeneratedChunkMark.claimMark(other),
                "a claim on one chunk says nothing about another");
    }

    /**
     * Something that cannot carry the mark - which is nothing in a running game, since the mixin
     * applies to {@code ChunkAccess} itself. Refusing to generate for it would look exactly like
     * "this dimension has no preset", which is the harder of the two failures to diagnose.
     */
    @Test
    void anUnmarkableChunkIsAlwaysAllowedToGenerate() {
        assertTrue(GeneratedChunkMark.claimMark(new Object()));
        assertTrue(GeneratedChunkMark.claimMark(null));
    }

    /**
     * The two callers cannot in fact race - the chunk pipeline orders {@code CARVERS} strictly
     * before {@code FEATURES} for a given chunk - but the claim is written so that it would not
     * matter if they did, and a property nobody exercises is a property nobody knows they have
     * broken. Hence one operation rather than a check followed by an act.
     */
    @Test
    void concurrentClaimsElectExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 500; attempt++) {
                Object chunk = new Marked();
                AtomicInteger winners = new AtomicInteger();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> a = pool.submit(() -> race(chunk, start, winners));
                Future<?> b = pool.submit(() -> race(chunk, start, winners));
                a.get();
                b.get();
                assertEquals(1, winners.get(),
                        "exactly one of two racing callers may generate a chunk (attempt " + attempt + ")");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void race(Object chunk, CyclicBarrier start, AtomicInteger winners) {
        try {
            start.await();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        if (GeneratedChunkMark.claimMark(chunk)) {
            winners.incrementAndGet();
        }
    }

    /** Stands in for what the mixin adds to every {@code ChunkAccess}, with the same locking. */
    private static final class Marked implements GeneratedChunkMark {
        private boolean generated;

        @Override
        public synchronized boolean urbex$claimGeneration() {
            if (generated) {
                return false;
            }
            generated = true;
            return true;
        }
    }
}
