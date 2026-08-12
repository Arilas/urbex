package dev.krona.urbex.worldgen.lost.cityassets;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CompiledPaletteSlotsTest {

    @Test
    public void weightsSummingToSlotCountAreTakenVerbatim() {
        // Upstream packs author weights as absolute counts out of 128; those must keep
        // generating identically.
        assertArrayEquals(new int[]{100, 20, 8},
                CompiledPalette.distributeSlots(new int[]{100, 20, 8}, 128));
    }

    @Test
    public void anOverfullListFillsInOrderAndTruncates() {
        // Lost Cities' rule, which every pack is authored against: a weight is an absolute slot
        // count, entries fill in declaration order, and the list stops when the array is full.
        // The first entry alone overflows, so it takes everything and the second gets nothing.
        assertArrayEquals(new int[]{128, 0},
                CompiledPalette.distributeSlots(new int[]{1000, 60}, 128));
    }

    @Test
    public void aTrailingHugeWeightFillsOnlyWhatIsLeft() {
        // The idiom this exists for: accents first, then "fill the rest". Read as proportions the
        // last entry would take 115 of 128 slots instead of 15, which is the difference between a
        // mossy stone wall and a solid moss cube. See CompiledPalette.distributeSlots.
        assertArrayEquals(new int[]{15, 10, 10, 30, 30, 15, 3, 15},
                CompiledPalette.distributeSlots(new int[]{15, 10, 10, 30, 30, 15, 3, 1000}, 128));
    }

    @Test
    public void theBundledIdiomSplitsEvenlyRatherThanBeingSwamped() {
        // urbex:blackstone is [32, 32, 1000] -- half accents, half base, not 94% base.
        assertArrayEquals(new int[]{32, 32, 64},
                CompiledPalette.distributeSlots(new int[]{32, 32, 1000}, 128));
    }

    @Test
    public void underweightEntriesAreScaledUp() {
        assertArrayEquals(new int[]{64, 64},
                CompiledPalette.distributeSlots(new int[]{1, 1}, 128));
    }

    @Test
    public void remainderSlotsGoToLargestFractionsThenLowestIndex() {
        // 128/3 = 42.67 each: two remainder slots, equal fractions, first two entries win.
        assertArrayEquals(new int[]{43, 43, 42},
                CompiledPalette.distributeSlots(new int[]{1, 1, 1}, 128));
    }

    @Test
    public void everySlotIsAssigned() {
        int[] weights = {7, 13, 1, 999, 40};
        int[] slots = CompiledPalette.distributeSlots(weights, 128);
        assertEquals(128, Arrays.stream(slots).sum());
    }

    @Test
    public void singleEntryTakesAllSlots() {
        // Under-full, so it scales up rather than throwing the way Lost Cities did.
        assertArrayEquals(new int[]{128}, CompiledPalette.distributeSlots(new int[]{3}, 128));
    }

    @Test
    public void weightsSummingToExactlySlotCountAreUnchangedByEitherPath() {
        assertArrayEquals(new int[]{64, 64}, CompiledPalette.distributeSlots(new int[]{64, 64}, 128));
    }

    @Test
    public void zeroTotalWeightIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CompiledPalette.distributeSlots(new int[]{0, 0}, 128));
    }

    @Test
    public void negativeWeightIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CompiledPalette.distributeSlots(new int[]{5, -1}, 128));
    }
}
