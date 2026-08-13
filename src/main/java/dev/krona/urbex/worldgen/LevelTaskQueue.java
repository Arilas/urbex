package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.setup.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Work a level's generation defers to the server thread, owned by that level.
 *
 * <p>This replaces {@code GlobalTodo}, a {@code static Map<ResourceKey<Level>, GlobalTodo>} keyed by
 * dimension id alone. Everything wrong with it followed from that (issue #127):</p>
 * <ul>
 *   <li><b>It outlived the world.</b> Nothing removed a dimension's bucket, so work queued in one
 *       single-player world was still there when the next world with the same dimension id loaded,
 *       and ran against it.</li>
 *   <li><b>Its bucket removal raced its enqueue.</b> The drain copied the map, emptied a chunk's
 *       queue and then removed that chunk's entry; an enqueue landing between the two put a task
 *       into a bucket that was about to be dropped.</li>
 *   <li><b>It allocated on every level tick, empty or not</b> - a {@code HashMap} copy and a
 *       {@code HashSet}, twenty times a second per dimension, to discover there was nothing to do.</li>
 *   <li><b>It discarded work whose chunk was not available.</b> The sapling task - the only task
 *       there has ever been - checked {@code hasChunksAt} and, finding the chunk gone, simply
 *       returned; the queue counted that as done. A tree that failed to grow because its chunk was
 *       briefly unloaded never grew.</li>
 * </ul>
 *
 * <p>A task now says which of those two things happened. {@link Outcome#DONE} retires it;
 * {@link Outcome#RETRY} keeps it, at the back of the queue, until it succeeds or ages out.</p>
 *
 * <p>Concurrent by construction: tasks are queued from worldgen worker threads while the drain runs
 * on the server thread. The queue is a {@link ConcurrentLinkedQueue} and the drain never removes
 * anything but the entries it has polled, so an enqueue racing a drain cannot be lost - it is either
 * polled by this pass or waiting for the next one.</p>
 */
public final class LevelTaskQueue {

    /** What a task did. */
    public enum Outcome {
        /** Finished, or established that it can never finish. Not queued again. */
        DONE,
        /** Could not run yet - the target chunk is not available. Queued again, at the back. */
        RETRY
    }

    @FunctionalInterface
    public interface Task {
        Outcome run(ServerLevel level);
    }

    /**
     * How many times a task may come back before it is given up on, with a log line naming it.
     *
     * <p>Counted in visits rather than ticks because a visit is what actually costs something: a
     * task the drain reaches every tick gets about a minute, and one it reaches less often gets
     * proportionally longer wall-clock, which is the right way round - a queue busy enough to defer
     * it is also a queue whose chunks are still settling. The point of the bound is that the
     * alternative to expiring is a queue that grows without limit behind a chunk nobody will ever
     * load again, and the point of it being a bound rather than a silent drop is that the old code
     * dropped this work on the first attempt and said nothing.</p>
     */
    static final int MAX_ATTEMPTS = 1200;

    /**
     * The drain's share of a tick. The count budget ({@code todoQueueSize}) alone does not bound
     * time: it counts tasks, and a task may place a tree. Whichever runs out first ends the pass.
     */
    static final long TIME_BUDGET_NANOS = 2_000_000L;      // 2ms of a 50ms tick

    /** Mutable so a retry costs no allocation; confined to the drain thread once polled. */
    private static final class Entry {
        private final BlockPos pos;
        private final Task task;
        private int attempts;

        Entry(BlockPos pos, Task task) {
            this.pos = pos;
            this.task = task;
        }
    }

    /** What to call this level in a log line. The queue never needs the level itself. */
    private final String owner;
    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();
    /**
     * Maintained alongside the queue because {@link ConcurrentLinkedQueue#size()} is O(n) and this
     * is read on every level tick of every level - and because the drain needs a pass limit that a
     * requeue cannot extend.
     */
    private final AtomicInteger pendingCount = new AtomicInteger();

    public LevelTaskQueue(String owner) {
        this.owner = owner;
    }

    public void add(BlockPos pos, Task task) {
        pending.add(new Entry(pos, task));
        pendingCount.incrementAndGet();
    }

    /**
     * Runs what fits in this tick's budget.
     *
     * <p>Returns immediately, having allocated nothing and touched neither the level nor the config,
     * when there is nothing queued - which is the overwhelmingly common case and was the one
     * {@code GlobalTodo} spent two allocations per tick per dimension on.</p>
     */
    public void drain(ServerLevel level) {
        int queued = pendingCount.get();
        if (queued == 0) {
            return;
        }
        // At most what was queued when the pass started: a task that retries goes to the back, and
        // without this limit a queue of nothing but retries would be walked over and over until the
        // count budget ran out, retrying each task several times in one tick.
        int budget = Math.min(Config.todoQueueSize(), queued);
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
        for (int done = 0; done < budget; done++) {
            Entry entry = pending.poll();
            if (entry == null) {
                break;
            }
            pendingCount.decrementAndGet();
            if (runAndRequeue(level, entry)) {
                // Requeued, so it is not this pass's problem again.
                pending.add(entry);
                pendingCount.incrementAndGet();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /** @return whether {@code entry} should go back on the queue */
    private boolean runAndRequeue(ServerLevel level, Entry entry) {
        Outcome outcome;
        try {
            outcome = entry.task.run(level);
        } catch (Exception e) {
            // One bad task must not stop the drain, and must not come back forever either.
            Urbex.getLogger().error("Deferred Urbex task at {} in '{}' failed; dropping it",
                    entry.pos, owner, e);
            return false;
        }
        if (outcome != Outcome.RETRY) {
            return false;
        }
        entry.attempts++;
        if (entry.attempts >= MAX_ATTEMPTS) {
            Urbex.getLogger().warn("Giving up on a deferred Urbex task at {} in '{}': its chunk has "
                            + "not been available for {} attempts.",
                    entry.pos, owner, entry.attempts);
            return false;
        }
        return true;
    }

    /**
     * Discards everything queued, for a level that is unloading or a server that is stopping.
     *
     * @return how many tasks were dropped, so the caller can say so rather than losing them quietly
     */
    public int retire() {
        int dropped = 0;
        while (pending.poll() != null) {
            pendingCount.decrementAndGet();
            dropped++;
        }
        return dropped;
    }

    public boolean isEmpty() {
        return pendingCount.get() == 0;
    }

    public int size() {
        return pendingCount.get();
    }
}
