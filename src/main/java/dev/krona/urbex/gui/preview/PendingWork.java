package dev.krona.urbex.gui.preview;

import dev.krona.urbex.Urbex;

import javax.annotation.Nullable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * One preview computation in flight at a time, keyed, latest request wins.
 *
 * <p>The preview's colour buffer used to be computed inside the widget's render pass. That is a
 * 62x58 chunk-planning walk - tens of milliseconds even once the assets stopped being recompiled
 * per click - and it ran between the frame starting and the frame being drawn, so it was a dropped
 * frame every time the player touched anything. Here the walk runs on a worker and the screen keeps
 * showing the image it already has until the new one is ready.</p>
 *
 * <h2>Why a slot and not a queue</h2>
 *
 * <p>A player dragging through presets generates requests far faster than the walk completes, and
 * every one of them supersedes the last: there is no value in finishing the third of five. A new
 * {@link #request} abandons whatever was in flight, so the queue never grows and the work that
 * completes is always the work someone still wants.</p>
 *
 * <p>Abandoned means abandoned, not cancelled: the worker is not interrupted, because the walk holds
 * no lock and touches nothing shared, so letting it finish into a result nobody takes is cheaper and
 * safer than tearing it down. {@link #take} is what refuses it.</p>
 *
 * <p>Every method here runs on the render thread; only the {@link Supplier} runs on the worker. That
 * is why none of this is synchronized - the {@link CompletableFuture} is the entire handoff.</p>
 *
 * @param <K> the caller's cache key, compared with {@code equals} to decide whether a finished
 *            result is still wanted.
 */
final class PendingWork<K> {

    private final Executor executor;

    @Nullable
    private CompletableFuture<int[]> future;
    @Nullable
    private K key;

    PendingWork(Executor executor) {
        this.executor = executor;
    }

    /**
     * Starts computing for {@code key}, abandoning whatever was in flight. {@code work} runs on the
     * worker and may throw - see {@link #take}.
     */
    void request(K key, Supplier<int[]> work) {
        this.key = key;
        this.future = CompletableFuture.supplyAsync(work, executor);
    }

    /** Whether a computation is in flight, or has finished and not been taken. */
    boolean isBusy() {
        return future != null;
    }

    /**
     * The finished result iff it is still {@code wanted}, and {@code null} otherwise - not yet
     * finished, superseded, or failed. Either way a finished computation frees the slot, so a
     * dropped result cannot wedge it.
     *
     * <p>A failure is logged and dropped rather than rethrown, because the render thread has nowhere
     * to put an exception except through the screen. It is logged loudly, though: the one failure
     * that is <em>expected</em> here - a momentarily self-contradictory preset, which is an ordinary
     * state while two paired sliders are being dragged - is caught by the computation itself, which
     * returns null for it. Anything that reaches this catch is a bug in a renderer's own math, and
     * the inline version this replaced was careful not to swallow those silently either.</p>
     */
    @Nullable
    int[] take(K wanted) {
        if (future == null || !future.isDone()) {
            return null;
        }
        CompletableFuture<int[]> finished = future;
        K finishedKey = key;
        future = null;
        key = null;
        int[] colors;
        try {
            colors = finished.join();
        } catch (CompletionException | CancellationException e) {
            Urbex.LOGGER.error("Urbex preview computation failed; keeping the previous image", e);
            return null;
        }
        return wanted.equals(finishedKey) ? colors : null;
    }

    /** Abandons anything in flight or finished. The worker, if running, is left to finish unseen. */
    void cancel() {
        future = null;
        key = null;
    }
}
