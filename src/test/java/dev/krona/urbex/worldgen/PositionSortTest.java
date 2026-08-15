package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@link PositionSort} is a drop-in for {@code Arrays.sort(long[])}, so every test here asserts the
 * same thing: the two agree, element for element.
 *
 * <p>That is the whole contract, and it is deliberately not "the output looks sorted". The
 * corrections pass writes to neighbours, so the order it visits positions in decides what the world
 * ends up looking like; a sort that were merely <em>a</em> valid order would change generated output
 * and move all five digest goldens. Equality with the implementation it replaces is what makes the
 * swap invisible.</p>
 */
class PositionSortTest {

    /**
     * Above {@code RADIX_THRESHOLD}, so this is the radix path rather than the delegation below it.
     * A chunk with a building in it writes tens of thousands of positions.
     */
    private static final int ABOVE_THRESHOLD = 4096;

    @Test
    void agreesWithArraysSortOnRealChunkPositions() {
        long[] positions = chunkPositions(37, -12, ABOVE_THRESHOLD, 0xC0FFEEL);

        assertAgreesWithArraysSort(positions);
    }

    /**
     * The case the sign flip exists for. {@code BlockPos.asLong} packs X into the top 26 bits, so
     * every position west of the origin has its sign bit set - an unsigned radix sort without the
     * flip would put the whole western hemisphere after the eastern one, and a world generated at
     * negative X would come out different from the same world generated at positive X.
     */
    @Test
    void agreesWithArraysSortAtNegativeCoordinates() {
        long[] positions = chunkPositions(-40, -40, ABOVE_THRESHOLD, 0x5EEDL);

        assertAgreesWithArraysSort(positions);
    }

    /**
     * Keys spanning both signs in one array. A driver's writes come from one chunk so this should
     * not arise in production, but the sort must not be the thing that assumes it.
     */
    @Test
    void agreesWithArraysSortAcrossTheSignBoundary() {
        Random random = new Random(0xA5A5A5L);
        long[] positions = new long[ABOVE_THRESHOLD];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = BlockPos.asLong(random.nextInt(-2048, 2048), random.nextInt(-64, 320),
                    random.nextInt(-2048, 2048));
        }

        assertAgreesWithArraysSort(positions);
    }

    /** Arbitrary longs, including {@code Long.MIN_VALUE}/{@code MAX_VALUE}, not just packed positions. */
    @Test
    void agreesWithArraysSortOnArbitraryLongs() {
        Random random = new Random(0xD00DL);
        long[] values = new long[ABOVE_THRESHOLD];
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextLong();
        }
        values[0] = Long.MIN_VALUE;
        values[1] = Long.MAX_VALUE;
        values[2] = 0L;
        values[3] = -1L;

        assertAgreesWithArraysSort(values);
    }

    /**
     * Duplicates must survive in the same number. The driver's map cannot produce them - it is keyed
     * by position - but a sort that silently deduplicated would drop corrections, and nothing else
     * here would notice.
     */
    @Test
    void keepsDuplicates() {
        long[] values = new long[ABOVE_THRESHOLD];
        Arrays.fill(values, BlockPos.asLong(8, 70, 8));
        values[0] = BlockPos.asLong(1, 2, 3);

        assertAgreesWithArraysSort(values);
    }

    /**
     * A chunk whose writes all share every varying byte. Every radix pass finds its byte constant
     * and is skipped, so this is the path where the sort does no scatter at all and must still
     * leave the array correct.
     */
    @Test
    void handlesAnArrayOfOneRepeatedValue() {
        long[] values = new long[ABOVE_THRESHOLD];
        Arrays.fill(values, BlockPos.asLong(-3, 64, 9));

        assertAgreesWithArraysSort(values);
    }

    /** Below the threshold it delegates, but the contract is the same and is worth pinning. */
    @Test
    void agreesWithArraysSortBelowTheRadixThreshold() {
        long[] positions = chunkPositions(2, 5, 64, 0xBEEFL);

        assertAgreesWithArraysSort(positions);
    }

    @Test
    void handlesEmptyAndSingleElementArrays() {
        assertAgreesWithArraysSort(new long[0]);
        assertAgreesWithArraysSort(new long[]{BlockPos.asLong(4, 5, 6)});
    }

    /**
     * The buffer is reused across calls on one thread, so a later, shorter sort runs against a
     * buffer still holding a longer one's data. Sorting descending input after ascending input is
     * the shape that catches a pass reading past its own length.
     */
    @Test
    void reusesItsBufferWithoutCarryingDataBetweenSorts() {
        assertAgreesWithArraysSort(chunkPositions(1, 1, ABOVE_THRESHOLD * 2, 0x11L));
        assertAgreesWithArraysSort(chunkPositions(1, 1, ABOVE_THRESHOLD, 0x22L));
        assertAgreesWithArraysSort(chunkPositions(-7, 3, ABOVE_THRESHOLD, 0x33L));
    }

    private static void assertAgreesWithArraysSort(long[] values) {
        long[] expected = values.clone();
        Arrays.sort(expected);
        long[] actual = values.clone();

        PositionSort.sort(actual);

        assertArrayEquals(expected, actual);
    }

    /**
     * {@code count} distinct positions inside one chunk, which is what the driver actually hands
     * the sort: X and Z confined to sixteen values each, Y spanning the world height.
     */
    private static long[] chunkPositions(int chunkX, int chunkZ, int count, long seed) {
        Random random = new Random(seed);
        long[] positions = new long[count];
        for (int i = 0; i < count; i++) {
            positions[i] = BlockPos.asLong(
                    (chunkX << 4) + random.nextInt(16),
                    random.nextInt(-64, 320),
                    (chunkZ << 4) + random.nextInt(16));
        }
        return positions;
    }
}
