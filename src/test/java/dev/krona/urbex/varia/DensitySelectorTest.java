package dev.krona.urbex.varia;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DensitySelectorTest {

    @Test
    void endpointsAreExact() {
        BlockPos pos = new BlockPos(10, 64, -10);
        assertFalse(DensitySelector.lighting(7L, pos, 0.0f));
        assertTrue(DensitySelector.lighting(7L, pos, 1.0f));
        assertFalse(DensitySelector.loot(7L, pos, 0.0f));
        assertTrue(DensitySelector.loot(7L, pos, 1.0f));
    }

    @Test
    void lightingAndLootAreIndependentAtTheSameMarkers() {
        boolean foundDifferentDecision = false;
        for (int y = 0; y < 256; y++) {
            BlockPos pos = new BlockPos(3, y, -7);
            if (DensitySelector.lighting(9L, pos, 0.5f) != DensitySelector.loot(9L, pos, 0.5f)) {
                foundDifferentDecision = true;
                break;
            }
        }
        assertTrue(foundDifferentDecision);
    }

    @Test
    void admissionIsMonotonicAndIterationOrderIndependent() {
        List<BlockPos> positions = IntStream.range(0, 512)
                .mapToObj(y -> new BlockPos(3, y, -7))
                .toList();
        Set<BlockPos> lightingLow = admitted(positions, pos -> DensitySelector.lighting(9L, pos, 0.25f));
        Set<BlockPos> lightingHigh = admitted(positions, pos -> DensitySelector.lighting(9L, pos, 0.75f));
        Set<BlockPos> lootLow = admitted(positions, pos -> DensitySelector.loot(9L, pos, 0.25f));
        Set<BlockPos> lootHigh = admitted(positions, pos -> DensitySelector.loot(9L, pos, 0.75f));
        assertTrue(lightingHigh.containsAll(lightingLow));
        assertTrue(lootHigh.containsAll(lootLow));

        List<BlockPos> reversed = new ArrayList<>(positions);
        Collections.reverse(reversed);
        assertEquals(lightingHigh, admitted(reversed, pos -> DensitySelector.lighting(9L, pos, 0.75f)));
        assertEquals(lootHigh, admitted(reversed, pos -> DensitySelector.loot(9L, pos, 0.75f)));
    }

    @Test
    void lootAdmissionIsPositionAddressedAndMonotonic() {
        BlockPos pos = new BlockPos(20, 70, 30);
        assertEquals(DensitySelector.loot(123L, pos, 0.4f), DensitySelector.loot(123L, pos, 0.4f));

        boolean low = DensitySelector.loot(123L, pos, 0.25f);
        boolean high = DensitySelector.loot(123L, pos, 0.75f);
        assertFalse(low && !high);

        boolean foundDifferentContainer = false;
        for (int x = 21; x < 277; x++) {
            if (DensitySelector.loot(123L, pos, 0.5f)
                    != DensitySelector.loot(123L, new BlockPos(x, 70, 30), 0.5f)) {
                foundDifferentContainer = true;
                break;
            }
        }
        assertTrue(foundDifferentContainer);
    }

    private static Set<BlockPos> admitted(List<BlockPos> positions, Predicate<BlockPos> predicate) {
        return positions.stream().filter(predicate).collect(Collectors.toSet());
    }

    @Test
    void densityCallsCannotPerturbVariantOrLootContentStreams() {
        BlockPos pos = new BlockPos(3, 64, -7);
        long variant = Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
                Rng.Purpose.LIGHTING_VARIANT).nextLong();
        long content = Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
                Rng.Purpose.LOOT).nextLong();

        DensitySelector.lighting(9L, pos, 0.25f);
        DensitySelector.lighting(9L, pos, 0.75f);
        DensitySelector.loot(9L, pos, 0.25f);
        DensitySelector.loot(9L, pos, 0.75f);

        assertEquals(variant, Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
                Rng.Purpose.LIGHTING_VARIANT).nextLong());
        assertEquals(content, Rng.atPos(9L, pos.getX(), pos.getY(), pos.getZ(),
                Rng.Purpose.LOOT).nextLong());
    }
}
