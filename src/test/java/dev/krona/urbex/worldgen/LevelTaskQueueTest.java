package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the level-owned queue does that {@code GlobalTodo} did not (issue #127, part b).
 * <p>
 * Every task here ignores the {@code ServerLevel} it is handed, and the drain is given
 * {@code null}: one cannot be constructed, and none of these rules are about the level. That is
 * also why the queue takes its dimension name as a string - the only thing it ever wanted the level
 * for was a log line.
 */
class LevelTaskQueueTest {

    @BeforeAll
    static void bootstrap() {
        // BlockPos wants the vanilla registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static LevelTaskQueue queue() {
        return new LevelTaskQueue("test:level");
    }

    @Test
    void aTaskThatFinishesRunsOnceAndIsGone() {
        LevelTaskQueue queue = queue();
        AtomicInteger runs = new AtomicInteger();
        queue.add(new BlockPos(1, 2, 3), level -> {
            runs.incrementAndGet();
            return LevelTaskQueue.Outcome.DONE;
        });

        queue.drain(null);
        queue.drain(null);

        assertEquals(1, runs.get());
        assertTrue(queue.isEmpty());
    }

    /**
     * The defect this half of #127 is named for. The sapling task checked {@code hasChunksAt}, found
     * the chunk unavailable, returned - and the queue counted that as done, so the tree never grew.
     * An unavailable chunk is now {@code RETRY}: the work waits rather than evaporating.
     */
    @Test
    void aTaskWhoseChunkIsNotAvailableIsRetriedRatherThanDropped() {
        LevelTaskQueue queue = queue();
        AtomicInteger attempts = new AtomicInteger();
        queue.add(new BlockPos(1, 2, 3), level ->
                attempts.incrementAndGet() < 3 ? LevelTaskQueue.Outcome.RETRY : LevelTaskQueue.Outcome.DONE);

        queue.drain(null);
        assertEquals(1, queue.size(), "still queued after the first refusal");
        queue.drain(null);
        assertEquals(1, queue.size());
        queue.drain(null);

        assertEquals(3, attempts.get());
        assertTrue(queue.isEmpty(), "and it is retired once it finally runs");
    }

    /**
     * Retrying cannot be forever, or a chunk nobody will ever load again grows the queue without
     * bound. It ages out with a log line naming the position - the point being that the old code's
     * answer to the same situation was a silent drop on the first attempt.
     */
    @Test
    void aTaskThatNeverBecomesRunnableAgesOutInsteadOfAccumulating() {
        LevelTaskQueue queue = queue();
        AtomicInteger attempts = new AtomicInteger();
        queue.add(new BlockPos(1, 2, 3), level -> {
            attempts.incrementAndGet();
            return LevelTaskQueue.Outcome.RETRY;
        });

        for (int tick = 0; tick < LevelTaskQueue.MAX_ATTEMPTS + 10 && !queue.isEmpty(); tick++) {
            queue.drain(null);
        }

        assertTrue(queue.isEmpty(), "the queue does not grow behind a chunk that never loads");
        assertEquals(LevelTaskQueue.MAX_ATTEMPTS, attempts.get());
    }

    /** A task that throws is dropped with a log line, and does not take the rest of the pass with it. */
    @Test
    void aThrowingTaskDoesNotStopTheDrain() {
        LevelTaskQueue queue = queue();
        AtomicInteger later = new AtomicInteger();
        queue.add(new BlockPos(1, 2, 3), level -> {
            throw new IllegalStateException("deliberate");
        });
        queue.add(new BlockPos(4, 5, 6), level -> {
            later.incrementAndGet();
            return LevelTaskQueue.Outcome.DONE;
        });

        queue.drain(null);

        assertEquals(1, later.get(), "the task behind the failure still ran");
        assertTrue(queue.isEmpty(), "and the failing one is not retried forever");
    }

    /**
     * One pass may not visit a retrying task twice. Without a limit taken before the pass starts, a
     * queue of nothing but retries is walked round and round until the count budget runs out.
     */
    @Test
    void oneTickVisitsEachTaskAtMostOnce() {
        LevelTaskQueue queue = queue();
        AtomicInteger attempts = new AtomicInteger();
        queue.add(new BlockPos(1, 2, 3), level -> {
            attempts.incrementAndGet();
            return LevelTaskQueue.Outcome.RETRY;
        });

        queue.drain(null);

        assertEquals(1, attempts.get());
    }

    /**
     * Deferred work dies with the level it was queued in. {@code GlobalTodo} never removed a
     * dimension's bucket, so a task queued in one single-player world was still there when the next
     * world with the same dimension id loaded - and ran against it.
     */
    @Test
    void retiringALevelDropsItsWorkAndSaysHowMuch() {
        LevelTaskQueue queue = queue();
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            queue.add(new BlockPos(i, 0, 0), level -> {
                runs.incrementAndGet();
                return LevelTaskQueue.Outcome.DONE;
            });
        }

        assertEquals(3, queue.retire());

        queue.drain(null);
        assertEquals(0, runs.get(), "nothing queued in the old level runs in the new one");
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.retire(), "and a second retirement has nothing left to report");
    }

    /**
     * An enqueue from a worldgen worker landing while the server thread drains. The old drain copied
     * the map, emptied a chunk's queue and then removed that chunk's entry, so a task arriving
     * between the two went into a bucket that was about to be dropped. Nothing may be lost here: a
     * task is either run by the pass it raced or still queued for the next one.
     */
    @Test
    void aConcurrentEnqueueDuringTheDrainCannotBeLost() throws Exception {
        for (int iteration = 0; iteration < 200; iteration++) {
            LevelTaskQueue queue = queue();
            List<Integer> ran = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 8; i++) {
                queue.add(new BlockPos(i, 0, 0), task(ran, i));
            }
            CountDownLatch start = new CountDownLatch(1);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> enqueue = executor.submit(() -> {
                    start.await();
                    queue.add(new BlockPos(99, 0, 0), task(ran, 99));
                    return null;
                });
                Future<?> drain = executor.submit(() -> {
                    start.await();
                    queue.drain(null);
                    return null;
                });
                start.countDown();
                enqueue.get();
                drain.get();
            }

            queue.drain(null);
            queue.drain(null);
            assertTrue(ran.contains(99), "the racing task ran, this pass or the next");
            assertEquals(9, ran.size(), "and nothing ran twice");
            assertTrue(queue.isEmpty());
        }
    }

    /**
     * An empty queue must cost nothing on a level tick. This is the one rule that a behavioural
     * assertion cannot express, so it is measured: {@code GlobalTodo} allocated a {@code HashMap}
     * copy and a {@code HashSet} <em>per level per tick</em> to discover it had nothing to do, which
     * over 100,000 ticks is megabytes. The bound below is generous enough not to be flaky and two
     * orders of magnitude under what the old shape would produce.
     */
    @Test
    void anEmptyQueueAllocatesNothingOnATick() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(threads instanceof com.sun.management.ThreadMXBean,
                "allocation accounting is a HotSpot extension");
        com.sun.management.ThreadMXBean hotspot = (com.sun.management.ThreadMXBean) threads;
        Assumptions.assumeTrue(hotspot.isThreadAllocatedMemorySupported());
        LevelTaskQueue queue = queue();

        for (int warmup = 0; warmup < 10_000; warmup++) {
            queue.drain(null);
        }
        long before = hotspot.getCurrentThreadAllocatedBytes();
        for (int tick = 0; tick < 100_000; tick++) {
            queue.drain(null);
        }
        long allocated = hotspot.getCurrentThreadAllocatedBytes() - before;

        assertTrue(allocated < 64 * 1024,
                "100,000 empty ticks allocated " + allocated + " bytes; an empty queue must not "
                        + "allocate per tick at all");
        assertFalse(queue.size() > 0);
    }

    private static LevelTaskQueue.Task task(List<Integer> ran, int id) {
        return level -> {
            ran.add(id);
            return LevelTaskQueue.Outcome.DONE;
        };
    }
}
