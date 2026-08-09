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
    public void overweightEntriesAreScaledProportionally() {
        // The bundled variants use weights like 1000: 1000/1060 and 60/1060 of 128 slots.
        assertArrayEquals(new int[]{121, 7},
                CompiledPalette.distributeSlots(new int[]{1000, 60}, 128));
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
        assertArrayEquals(new int[]{128}, CompiledPalette.distributeSlots(new int[]{3}, 128));
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
