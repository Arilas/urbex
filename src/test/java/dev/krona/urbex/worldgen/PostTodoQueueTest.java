package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three ways the old arrangement could lose or misdirect a post-generation callback, and why a
 * queue owned by the {@link ChunkGenContext} closes all three (issue #127).
 * <p>
 * Post-todos used to be stored on the cached {@code ChunkPlan} for the chunk, which
 * {@code ChunkFixer} re-fetched through {@code DimensionCaches} in order to drain. That cache entry
 * outlives the generation that wrote to it and is shared by every generation that reads the chunk,
 * so the work could be evicted before the drain, inherited by a second generation, or cleared out
 * from under a concurrent enqueue.
 */
class PostTodoQueueTest {

    @BeforeAll
    static void bootstrap() {
        // DimensionCaches -> TimedCache -> Config, and BlockPos, both want the vanilla registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void closeAndDrainRejectsLateEnqueueInsteadOfSilentlyDroppingIt() {
        PostTodoQueue queue = new PostTodoQueue();
        BlockPos admitted = new BlockPos(49, 70, -31);
        queue.add(admitted, recordInto(new ArrayList<>(), admitted));

        Map<BlockPos, Consumer<WorldGenLevel>> drained = queue.closeAndDrain();

        assertEquals(List.of(admitted), List.copyOf(drained.keySet()));
        assertThrows(IllegalStateException.class,
                () -> queue.add(new BlockPos(50, 70, -31), level -> { }));
    }

    /**
     * The drain happens once, on the context that owns the work. A second drain is a bug - the
     * callbacks would run twice against a region that has already been finished - and the old
     * cache-fetching drain could not tell the difference between the first and the second.
     */
    @Test
    void aGenerationQueueCanOnlyBeDrainedOnce() {
        PostTodoQueue queue = new PostTodoQueue();
        queue.add(new BlockPos(49, 70, -31), level -> { });

        queue.closeAndDrain();

        assertThrows(IllegalStateException.class, queue::closeAndDrain);
    }

    /**
     * Duplicate generation of one chunk. Both generations address the same position - the same
     * building part, driven twice - and each must see only what it queued itself. Sharing one map
     * on the cached {@code ChunkPlan} meant the first drain took the second generation's
     * callback with it and applied it to the wrong region.
     */
    @Test
    void twoGenerationsOfTheSameChunkDoNotSeeEachOthersWork() {
        BlockPos contested = new BlockPos(49, 70, -31);
        List<String> ran = new ArrayList<>();
        PostTodoQueue first = new PostTodoQueue();
        PostTodoQueue second = new PostTodoQueue();
        first.add(contested, level -> ran.add("first"));
        second.add(contested, level -> ran.add("second"));

        first.closeAndDrain().forEach((pos, todo) -> todo.accept(null));
        assertEquals(List.of("first"), ran);

        second.closeAndDrain().forEach((pos, todo) -> todo.accept(null));
        assertEquals(List.of("first", "second"), ran);
    }

    /**
     * Forced eviction of everything the dimension caches hold, in the window between queueing the
     * work and draining it. That window used to be fatal: the callbacks lived in the evicted
     * {@code ChunkPlan}, and the drain re-fetched the cache and found a fresh, empty one.
     */
    @Test
    void forcedCacheEvictionBetweenQueueingAndDrainingCannotLoseWork() {
        DimensionCaches caches = new DimensionCaches(1337L);
        PostTodoQueue queue = new PostTodoQueue();
        BlockPos pos = new BlockPos(49, 70, -31);
        List<BlockPos> ran = new ArrayList<>();
        queue.add(pos, recordInto(ran, pos));

        caches.clear();

        queue.closeAndDrain().forEach((p, todo) -> todo.accept(null));
        assertEquals(List.of(pos), ran);
    }

    /** Last write wins per position, which is what the map on {@code ChunkPlan} did. */
    @Test
    void aSecondTodoAtOnePositionReplacesTheFirst() {
        PostTodoQueue queue = new PostTodoQueue();
        BlockPos pos = new BlockPos(49, 70, -31);
        List<String> ran = new ArrayList<>();
        queue.add(pos, level -> ran.add("earlier"));
        queue.add(pos, level -> ran.add("later"));

        queue.closeAndDrain().forEach((p, todo) -> todo.accept(null));

        assertEquals(List.of("later"), ran);
    }

    /**
     * An enqueue racing the drain is linearizable: it either lands before the close and is in the
     * snapshot, or it is refused. What it may not do is succeed into a queue nobody will ever drain.
     */
    @Test
    void racingEnqueueIsEitherInTheDrainOrExplicitlyRejected() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int iteration = 0; iteration < 100; iteration++) {
                PostTodoQueue queue = new PostTodoQueue();
                BlockPos pos = new BlockPos(49, 70, -31);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> enqueue = executor.submit(() -> {
                    start.await();
                    try {
                        queue.add(pos, level -> { });
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                });
                Future<Map<BlockPos, Consumer<WorldGenLevel>>> drain = executor.submit(() -> {
                    start.await();
                    return queue.closeAndDrain();
                });

                boolean accepted = enqueue.get();
                Map<BlockPos, Consumer<WorldGenLevel>> drained = drain.get();
                if (accepted) {
                    assertEquals(List.of(pos), List.copyOf(drained.keySet()));
                } else {
                    assertTrue(drained.isEmpty());
                }
            }
        }
    }

    /** The callbacks here only record that they ran; nothing in this suite has a world to write to. */
    private static Consumer<WorldGenLevel> recordInto(List<BlockPos> ran, BlockPos pos) {
        return level -> ran.add(pos);
    }
}
