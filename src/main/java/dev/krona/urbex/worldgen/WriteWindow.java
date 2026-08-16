package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * The band of Y one generation may write in, inclusive at both ends.
 *
 * <p>The level's own bounds for a chunk Urbex generates on its own behalf, and a {@link SiteBinding
 * site}'s window when another mod named one. Two things read it, for two different reasons:</p>
 *
 * <ul>
 *   <li>{@link ChunkBuffer}, which every driver write passes through, so a pass that believes it may
 *       build to the sky writes nothing above the window whatever it believes.</li>
 *   <li>{@link ChunkGenContext}'s three deferred queues, which <em>bypass</em> the driver - they
 *       hand a callback to the world to run later - so the buffer never sees them.</li>
 * </ul>
 *
 * <p>A value rather than two ints on the context, because the intersection rule below is the kind of
 * arithmetic that is obviously right until it is quietly wrong at one end, and it is worth being
 * able to assert about on its own.</p>
 */
public record WriteWindow(int minY, int maxY) {

    /**
     * The window for a generation in a level bounded by {@code [levelMinY, levelMaxY]}.
     *
     * <p>A site's window is intersected with the level's rather than trusted: a caller may name a
     * window wider than the dimension - a sensible thing to do when the caller does not know which
     * dimension it will be asked about - and the section table a buffer allocates is sized for the
     * dimension.</p>
     */
    public static WriteWindow of(@Nullable SiteBinding site, int levelMinY, int levelMaxY) {
        if (site == null) {
            return new WriteWindow(levelMinY, levelMaxY);
        }
        return new WriteWindow(Math.max(site.minY(), levelMinY), Math.min(site.maxY(), levelMaxY));
    }

    public boolean contains(int y) {
        return y >= minY && y <= maxY;
    }

    /**
     * Whether a deferred write anchored at {@code pos} is inside the window.
     *
     * <p>Exact for the block a todo names and approximate for anything its callback touches around
     * it - the upper half of a door, the block a light is attached to. The queues hold opaque
     * callbacks, so the anchor is the only thing there is to test. What this buys is that a site
     * cannot place a chest, a spawner or a light one block outside its window, which is what these
     * queues are used for; what it does not buy is a proof about the block beside it.</p>
     */
    public boolean contains(BlockPos pos) {
        return contains(pos.getY());
    }
}
