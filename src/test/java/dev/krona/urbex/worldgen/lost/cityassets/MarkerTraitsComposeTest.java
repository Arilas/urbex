package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.Rule;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a marker carrying several traits amounts to, which version 1 got wrong for its whole lifetime.
 *
 * <p>{@code Parts.generatePart} used to test the four metadata fields in an {@code else if} chain, so a
 * marker carrying two of them applied the first and dropped the rest without a word. These tests are
 * about {@link Palette.Info#applied()}, which is the list that loop walks: the loop itself is a
 * {@code switch} with one arm per constant and no fall-through, so the list is the whole of what decides
 * which traits are applied and in what order.</p>
 */
class MarkerTraitsComposeTest {

    private static final CompoundTag NBT = new CompoundTag();

    @Rule("TRAIT.004")
    @Test
    void aMarkerCarryingBothALightAndAMobAppliesBothRatherThanOnlyTheFirstOne() {
        Palette.Info both = Palette.Info.of("urbex:easymobs", null, inPlaceLight(), null);

        assertEquals(List.of(MarkerTrait.SPAWNER, MarkerTrait.LIGHT), both.applied(),
                "a spawner beside a light must apply both traits; the else-if chain this replaces "
                        + "applied the light and lost the mob");
    }

    @Rule("TRAIT.004")
    @Test
    void aMarkerCarryingAllFourTraitsAppliesAllFourInTheOrderTheSpecificationDefinesThem() {
        Palette.Info everything =
                Palette.Info.of("urbex:easymobs", "urbex:chest", inPlaceLight(), NBT);

        assertEquals(
                List.of(MarkerTrait.LOOT, MarkerTrait.SPAWNER, MarkerTrait.BLOCK_ENTITY,
                        MarkerTrait.LIGHT),
                everything.applied(),
                "the order is 01-traits.md §4's, read off the specification rather than chosen");
    }

    @Test
    void aMarkerCarryingOneTraitAppliesExactlyThatOneSoNothingThatShipsTodayCanMove() {
        assertEquals(List.of(MarkerTrait.LIGHT),
                Palette.Info.of(null, null, inPlaceLight(), null).applied());
        assertEquals(List.of(MarkerTrait.SPAWNER),
                Palette.Info.of("urbex:easymobs", null, null, null).applied());
        assertEquals(List.of(MarkerTrait.LOOT),
                Palette.Info.of(null, "urbex:chest", null, null).applied());
        assertEquals(List.of(MarkerTrait.BLOCK_ENTITY),
                Palette.Info.of(null, null, null, NBT).applied());
    }

    @Test
    void anEmptyLootOrMobStringAppliesNothingExactlyAsTheChainItReplacesDidNot() {
        Palette.Info empty = Palette.Info.of("", "", null, null);

        assertTrue(empty.applied().isEmpty(),
                "version 1 guarded both fields on isEmpty() as well as on null, and a marker that is "
                        + "'special' enough to skip the block's other handling but applies nothing is "
                        + "behaviour this change deliberately preserves rather than fixes");
        assertTrue(empty.isSpecial(),
                "isSpecial still answers the same way, which is what keeps the surrounding branch "
                        + "unchanged");
    }

    @Test
    void aMarkerWithNoMetadataCarriesNoTraitsAtAll() {
        assertTrue(Palette.Info.of(null, null, null, null).applied().isEmpty());
    }

    @Test
    void theAppliedListIsImmutableSoNoGenerationPassCanEditWhatAMarkerCarries() {
        List<MarkerTrait> applied = Palette.Info.of("urbex:easymobs", null, null, null).applied();

        assertThrows(UnsupportedOperationException.class, () -> applied.add(MarkerTrait.LIGHT));
    }

    /** An in-place light source: no socket pool, and air behind it when the light is off. */
    private static LightSource inPlaceLight() {
        return new LightSource(null, BlockChoice.AIR);
    }
}
