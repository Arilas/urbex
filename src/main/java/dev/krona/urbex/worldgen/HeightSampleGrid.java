package dev.krona.urbex.worldgen;

/**
 * The tiling of the chunk grid into square blocks that share one sampled terrain height.
 *
 * <p>{@code heightSampleSize} chunks on a side are given the height of a single sampled chunk, which
 * is what makes a city sit on one level instead of following every undulation. The cache is filled a
 * whole block at a time by whichever chunk in it asked first, so the tiling has to be a genuine
 * partition: every chunk in exactly one block, and the sampled coordinate a function of the block
 * rather than of the chunk that happened to ask. Otherwise the published height depends on
 * generation order (issue #126).</p>
 *
 * <p>It did not used to be. The anchor was {@code (c / size) * size}, which truncates towards zero,
 * and the block was then laid out away from the origin with a {@code -1} step for negative
 * coordinates. Row 0 and column 0 therefore belonged to two blocks at once - the one stepping up
 * from 0 and the one stepping down from it - each sampling a different coordinate, and whichever
 * chunk reached the cache first published its answer for the overlap. Nothing else in the grid
 * overlapped, so only worlds generated across the origin could see it.</p>
 */
public final class HeightSampleGrid {

    private HeightSampleGrid() {
    }

    /**
     * The lowest chunk coordinate of the sample block {@code coordinate} belongs to.
     *
     * <p>{@link Math#floorDiv} rather than {@code /}: it rounds towards negative infinity for both
     * signs, so blocks tile the line without a seam at the origin. For non-negative coordinates it
     * agrees with {@code /} exactly, which is why worlds generated away from the origin never
     * observed the difference.</p>
     */
    public static int anchor(int coordinate, int heightSampleSize) {
        if (heightSampleSize <= 1) {
            return coordinate;
        }
        return Math.floorDiv(coordinate, heightSampleSize) * heightSampleSize;
    }

    /**
     * The chunk coordinate whose terrain is sampled for every chunk of the block starting at
     * {@code anchor} - its centre, so the height is representative of the block rather than of one
     * of its corners.
     */
    public static int sampler(int anchor, int heightSampleSize) {
        if (heightSampleSize <= 1) {
            return anchor;
        }
        return anchor + heightSampleSize / 2;
    }
}
