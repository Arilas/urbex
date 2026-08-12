package dev.krona.urbex.worldgen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifetime rules {@link GenerationSession} keeps its level runtimes by (issue #125): load,
 * unload, and the fact that a server's runtimes die with that server.
 * <p>
 * A {@code /reload} no longer appears here. It used to rebuild every published runtime through a
 * {@code republish}; it now swaps one {@link TagEpoch} instead, because block tags were the only
 * thing in a runtime a reload could change (issue #128). The epoch-retention property those tests
 * held - a reader keeps what it was handed, a swap decides what the next reader gets - moved with
 * it, to {@link TagEpochTest}.
 * <p>
 * Driven through plain strings rather than {@code ServerLevel}s. That is why the repository is a
 * separate, generic class: a {@code ServerLevel} cannot be constructed, subclassed usefully or
 * proxied, so rules expressed directly against one are only reachable from a running dedicated
 * server - and these are exactly the rules that were previously wrong in ways no test caught.
 */
class RuntimeRepositoryTest {

    @Test
    void aPublishedRuntimeIsWhatTheNextLookupFinds() {
        RuntimeRepository<String, String> repository = new RuntimeRepository<>();

        assertNull(repository.find("overworld"), "nothing generates in a level that never loaded");
        repository.publish("overworld", "runtime-1");

        assertEquals("runtime-1", repository.find("overworld"));
        assertEquals(1, repository.size());
    }

    @Test
    void unloadingALevelRetiresItsRuntime() {
        RuntimeRepository<String, String> repository = new RuntimeRepository<>();
        repository.publish("overworld", "runtime-1");

        assertEquals("runtime-1", repository.retire("overworld"));

        assertNull(repository.find("overworld"));
        assertEquals(0, repository.size());
    }

    /**
     * The property the whole ownership move exists for. A chunk that is already generating holds
     * the runtime it started with; publishing a replacement decides what the <em>next</em> chunk
     * picks up and cannot reach into the epoch already in flight. The dirty counter did the opposite
     * - it cleared shared state in place, from whichever thread happened to notice.
     */
    @Test
    void publishingAReplacementLeavesTheOneAlreadyInFlightAlone() {
        RuntimeRepository<String, String> repository = new RuntimeRepository<>();
        repository.publish("overworld", "runtime-1");
        String inFlight = repository.find("overworld");

        repository.publish("overworld", "runtime-2");

        assertEquals("runtime-1", inFlight, "work already holding an epoch keeps it");
        assertEquals("runtime-2", repository.find("overworld"));
    }

    /**
     * Two servers started in sequence in one JVM. The second gets its own repository, so it cannot
     * inherit the first's runtimes - which is what a process-global map keyed by dimension id did
     * until something remembered to bump a counter.
     */
    @Test
    void aClosedRepositoryKeepsNothingAndAcceptsNothing() {
        RuntimeRepository<String, String> first = new RuntimeRepository<>();
        first.publish("overworld", "runtime-1");

        first.close();

        assertTrue(first.isClosed());
        assertNull(first.find("overworld"));
        assertThrows(IllegalStateException.class, () -> first.publish("overworld", "runtime-2"),
                "a runtime published into a stopped server would never be retired again");

        RuntimeRepository<String, String> second = new RuntimeRepository<>();
        assertNull(second.find("overworld"), "the next server starts with nothing published");
    }

    /**
     * Closing hands each runtime over on the way out, which is how a stopping server gets to say
     * what its levels still had queued instead of dropping it silently ({@link LevelTaskQueue}).
     */
    @Test
    void closingHandsEveryRetiredRuntimeToTheCaller() {
        RuntimeRepository<String, String> repository = new RuntimeRepository<>();
        repository.publish("overworld", "runtime-1");
        repository.publish("nether", "runtime-2");
        List<String> retired = new java.util.ArrayList<>();

        repository.close((key, value) -> retired.add(key + "=" + value));

        assertEquals(List.of("nether=runtime-2", "overworld=runtime-1"),
                retired.stream().sorted().toList());
        assertEquals(0, repository.size());
    }

    @Test
    void closingTwiceIsHarmless() {
        RuntimeRepository<String, String> repository = new RuntimeRepository<>();
        repository.close();
        repository.close();
        assertTrue(repository.isClosed());
    }

    /**
     * Replacements landing while chunks are generating. Every lookup must answer with some complete
     * runtime - never null, never a half-built one - which is the difference between publishing a
     * finished replacement and the clear-then-refill the counter protocol performed.
     */
    @Test
    void aReplacementOverlappingGenerationNeverExposesClearedState() throws Exception {
        RuntimeRepository<String, String> repository = new RuntimeRepository<>();
        repository.publish("overworld", "runtime-0");
        AtomicInteger epoch = new AtomicInteger();
        CountDownLatch readerStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<String>> readers = executor.submit(() -> {
                readerStarted.countDown();
                List<String> seen = new java.util.ArrayList<>();
                for (int i = 0; i < 10_000; i++) {
                    seen.add(repository.find("overworld"));
                }
                return seen;
            });
            Future<?> writers = executor.submit(() -> {
                readerStarted.await();
                for (int i = 0; i < 500; i++) {
                    repository.publish("overworld", "runtime-" + epoch.incrementAndGet());
                }
                return null;
            });

            writers.get();
            for (String seen : readers.get()) {
                assertNotNull(seen, "a lookup during a replacement must never find the level unpublished");
                assertTrue(seen.startsWith("runtime-"), "and never a value that is not a runtime");
            }
        }
    }

    @Test
    void retiringSomethingNeverPublishedIsHarmless() {
        RuntimeRepository<String, String> repository = new RuntimeRepository<>();
        assertNull(repository.retire("overworld"));
        assertSame(0, repository.size());
    }
}
