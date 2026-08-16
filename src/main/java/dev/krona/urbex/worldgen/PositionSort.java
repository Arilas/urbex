package dev.krona.urbex.worldgen;

import java.util.Arrays;

/**
 * Sorts the driver's written positions, in the order {@link java.util.Arrays#sort(long[])} would.
 *
 * <p>The corrections pass has to run in a deterministic order - it writes to neighbours, so the
 * result depends on which position is corrected first, and sorting is what keeps that from
 * depending on write order (see {@code ChunkDriver.correctionsPass}). Sorting is therefore not
 * optional. Sorting <em>this way</em> is: measured against the radius-40 soak, {@code Arrays.sort}
 * over a chunk's ~11k packed positions cost 489us per generated chunk, which was 43% of the whole
 * corrections pass and about a sixth of everything Urbex spends on a chunk.</p>
 *
 * <p>Quicksort is the wrong shape for this input. It is O(n log n) <em>comparisons</em> - about
 * 147k of them for 11k elements - and each is an unpredictable branch. These keys are
 * {@link net.minecraft.core.BlockPos#asLong} values from a single chunk, so most of their bits are
 * identical across the whole array: X and Z contribute four varying bits each and Y about nine, and
 * the other forty-odd bits are the chunk's own coordinates repeated 11,000 times. An LSD radix sort
 * does a fixed number of branch-free passes and skips every byte that turns out to be constant, so
 * this input costs four or five passes rather than eight.</p>
 *
 * <h2>Why this cannot move the digest</h2>
 *
 * <p>It is a sort, and it produces the same total order as the one it replaces, so the corrections
 * pass visits the same positions in the same sequence and writes the same blocks. That is a
 * stronger guarantee than "equivalent output": there is no output to compare, because the input to
 * everything downstream is byte-identical. {@link PositionSortTest} pins it against
 * {@code Arrays.sort} directly, and the five digest goldens cover it end to end.</p>
 */
final class PositionSort {

    /**
     * Below this, {@code Arrays.sort} wins and this is not worth the buffer.
     *
     * <p>Radix pays a fixed cost per pass - a 256-entry histogram and two walks of the array -
     * that quicksort does not, so it only pays off once n is large enough to amortise it. A chunk
     * with a building in it writes tens of thousands of positions and is far above this; a chunk
     * that only clipped a street corner may write a few dozen and is far below. Both happen, so
     * both paths are real rather than one being a formality.</p>
     */
    private static final int RADIX_THRESHOLD = 512;

    /**
     * The scratch half of the double buffer, one per worldgen thread.
     *
     * <p>Per thread rather than per driver because a driver lives for one chunk: a buffer it owned
     * would be allocated once per chunk, which is the allocation this is trying not to add. The
     * worldgen pool is a fixed handful of long-lived threads, so this is a bounded number of arrays
     * that stop growing once each thread has met its biggest chunk.</p>
     */
    private static final ThreadLocal<long[]> SCRATCH = new ThreadLocal<>();

    private PositionSort() {
    }

    /** Sorts {@code keys} ascending, in place, exactly as {@code Arrays.sort(keys)} would. */
    static void sort(long[] keys) {
        int n = keys.length;
        if (n < RADIX_THRESHOLD) {
            Arrays.sort(keys);
            return;
        }
        long[] buffer = scratch(n);
        int[] counts = new int[256];
        long[] from = keys;
        long[] to = buffer;
        boolean sortedIntoBuffer = false;

        for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
            int shift = byteIndex << 3;
            Arrays.fill(counts, 0);
            int firstBucket = bucket(from[0], shift, byteIndex);
            boolean constant = true;
            for (int i = 0; i < n; i++) {
                int b = bucket(from[i], shift, byteIndex);
                counts[b]++;
                constant &= b == firstBucket;
            }
            // Every key agrees on this byte, so a pass over it would copy the array unchanged. This
            // is the common case here rather than a lucky one - see the class note on which bits of
            // a single chunk's positions actually vary.
            if (constant) {
                continue;
            }
            int start = 0;
            for (int i = 0; i < 256; i++) {
                int count = counts[i];
                counts[i] = start;
                start += count;
            }
            for (int i = 0; i < n; i++) {
                long value = from[i];
                to[counts[bucket(value, shift, byteIndex)]++] = value;
            }
            long[] swap = from;
            from = to;
            to = swap;
            sortedIntoBuffer = !sortedIntoBuffer;
        }
        if (sortedIntoBuffer) {
            System.arraycopy(from, 0, keys, 0, n);
        }
    }

    /**
     * Which bucket {@code value} lands in for this pass.
     *
     * <p>The top byte is flipped. Radix sorts unsigned, {@code Arrays.sort} sorts signed, and these
     * keys really do go negative: {@code BlockPos.asLong} puts X in the top 26 bits, so every
     * position west of the origin packs with its sign bit set. Flipping the sign bit of the most
     * significant byte maps signed order onto unsigned order, which is what makes this a drop-in
     * replacement rather than one that agrees only in the eastern hemisphere.</p>
     */
    private static int bucket(long value, int shift, int byteIndex) {
        int b = (int) ((value >>> shift) & 0xFF);
        return byteIndex == 7 ? b ^ 0x80 : b;
    }

    /** This thread's scratch buffer, grown if this is the biggest chunk it has seen. */
    private static long[] scratch(int length) {
        long[] buffer = SCRATCH.get();
        if (buffer == null || buffer.length < length) {
            buffer = new long[length];
            SCRATCH.set(buffer);
        }
        return buffer;
    }
}
