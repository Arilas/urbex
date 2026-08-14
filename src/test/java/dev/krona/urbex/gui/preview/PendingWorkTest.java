package dev.krona.urbex.gui.preview;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview's one-slot job queue, which is what lets the recompute leave the render thread.
 *
 * <p>Everything here is exercised on a direct executor, so the ordering under test is the class's
 * own rather than a scheduler's. The threading it exists to survive - a worker finishing while the
 * player is already three presets further on - is expressed as a superseded request, which is what
 * that looks like from the render thread.</p>
 */
class PendingWorkTest {

    /** Runs the work on the calling thread, so "in flight" and "finished" are decidable in a test. */
    private static final Executor DIRECT = Runnable::run;

    @Test
    void afinishedResultIsHandedBackToTheKeyThatAskedForIt() {
        PendingWork<String> work = new PendingWork<>(DIRECT);

        work.request("map", () -> new int[]{1, 2, 3});

        assertArrayEquals(new int[]{1, 2, 3}, work.take("map"));
    }

    @Test
    void aResultIsHandedBackOnceAndThenTheSlotIsFree() {
        PendingWork<String> work = new PendingWork<>(DIRECT);
        work.request("map", () -> new int[]{1});

        work.take("map");

        assertNull(work.take("map"), "the second poll of the same frame must not re-upload");
        assertFalse(work.isBusy(), "and the slot is free for the next request");
    }

    /**
     * The case the whole class is for: the player switches preset again before the first computation
     * lands. The stale image must never reach the screen, however it finishes.
     */
    @Test
    void asupersededRequestNeverDeliversItsResult() {
        PendingWork<String> work = new PendingWork<>(DIRECT);
        work.request("first", () -> new int[]{1});

        work.request("second", () -> new int[]{2});

        assertArrayEquals(new int[]{2}, work.take("second"),
                "the slot holds the latest request, not the one it replaced");
        assertFalse(work.isBusy(), "and the abandoned result is not queued up behind it");
    }

    @Test
    void aresultForAKeyNobodyWantsIsDroppedRatherThanShown() {
        PendingWork<String> work = new PendingWork<>(DIRECT);
        work.request("stale", () -> new int[]{1});

        assertNull(work.take("current"));
        assertFalse(work.isBusy(), "dropping it frees the slot rather than wedging it");
    }

    /**
     * A computation can throw: {@code PresetRoadGrid.of} refuses a momentarily self-contradictory
     * preset, which is an ordinary state while two paired sliders are being dragged. The render
     * thread must not see that exception - it keeps the last good image, as it did when the compute
     * ran inline.
     */
    @Test
    void afailedComputationIsDroppedRatherThanThrownAtTheRenderThread() {
        PendingWork<String> work = new PendingWork<>(DIRECT);
        work.request("boom", () -> {
            throw new IllegalArgumentException("minimum exceeds maximum");
        });

        assertNull(work.take("boom"));
        assertFalse(work.isBusy());
    }

    @Test
    void cancellingDropsAFinishedResult() {
        PendingWork<String> work = new PendingWork<>(DIRECT);
        work.request("map", () -> new int[]{1});

        work.cancel();

        assertNull(work.take("map"), "a closed preview must not upload work started before it closed");
        assertFalse(work.isBusy());
    }

    @Test
    void workInFlightIsNotYetAResult() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            PendingWork<String> work = new PendingWork<>(pool);

            work.request("map", () -> {
                started.countDown();
                await(release);
                return new int[]{7};
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertNull(work.take("map"), "an unfinished computation must not block the render thread");
            assertTrue(work.isBusy());

            release.countDown();
            int[] result = null;
            for (int i = 0; i < 500 && result == null; i++) {
                result = work.take("map");
                if (result == null) {
                    Thread.sleep(10);
                }
            }
            assertArrayEquals(new int[]{7}, result);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
