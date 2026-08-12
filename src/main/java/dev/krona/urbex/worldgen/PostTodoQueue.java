package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Deferred block writes owned by one chunk-generation context, drained at the end of that same
 * generation.
 *
 * <p>These used to live on the cached {@code BuildingInfo} for the chunk, and {@code ChunkFixer}
 * re-fetched that cache entry to drain them. Three things could go wrong with that, and the queue
 * being per-context is what removes all three (issue #127): the cache entry could be evicted between
 * the write and the drain, taking the callbacks with it; a second generation of the same chunk found
 * the first generation's callbacks still queued and ran them against its own region; and a callback
 * added on a worker thread while another thread cleared the map was simply lost.</p>
 *
 * <p>Keyed by position and last-write-wins, which is what the map on {@code BuildingInfo} did:
 * a position written twice in one generation gets the later callback only. Iteration order is
 * deliberately the {@link ConcurrentHashMap} one rather than insertion order - the drain applies
 * these to the world in that order, and preserving it keeps this change a pure ownership move. The
 * write-recording digest does not cover post-todos at all (they go through the region, not the
 * {@link ChunkDriver}), so it would not have noticed a reordering here either way.</p>
 *
 * <p>The closed flag is the same guarantee {@link LightTodoQueue} makes: a racing enqueue either
 * completes before the drain and is in that snapshot, or observes the closed state and fails loudly.
 * Nothing can be admitted into a context that has already been drained and discarded.</p>
 */
final class PostTodoQueue {

    private final Map<BlockPos, Consumer<WorldGenLevel>> pending = new ConcurrentHashMap<>();
    private boolean closed;

    synchronized void add(BlockPos pos, Consumer<WorldGenLevel> todo) {
        if (closed) {
            throw new IllegalStateException("Cannot admit a post-generation todo at " + pos
                    + " after the generation queue was drained");
        }
        pending.put(pos, todo);
    }

    /**
     * Closes the queue and hands back what it holds, once.
     *
     * <p>A view of the live map rather than a copy: {@code Map.copyOf} would rehash into a
     * different iteration order, and the drain order is the order these callbacks reach the world.
     * The queue is discarded with its context immediately afterwards, so the view outlives nothing.</p>
     */
    synchronized Map<BlockPos, Consumer<WorldGenLevel>> closeAndDrain() {
        if (closed) {
            throw new IllegalStateException("Generation post-todo queue was already drained");
        }
        closed = true;
        return Collections.unmodifiableMap(pending);
    }
}
