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

        assertEquals(List.of(MarkerTrait.LIGHT, MarkerTrait.SPAWNER), both.applied(),
                "a spawner beside a light must apply both traits; the else-if chain this replaces "
                        + "applied the light and lost the mob");
    }

    @Rule("TRAIT.004")
    @Rule("TRAIT.095")
    @Test
    void aMarkerCarryingAllFourTraitsAppliesAllFourInTheOrderTheSpecificationDefinesThem() {
        Palette.Info everything =
                Palette.Info.of("urbex:easymobs", "urbex:chest", inPlaceLight(), NBT);

        assertEquals(
                List.of(MarkerTrait.LIGHT, MarkerTrait.LOOT, MarkerTrait.SPAWNER,
                        MarkerTrait.BLOCK_ENTITY),
                everything.applied(),
                "TRAIT.095's phase order: selection first, then the three decorators");
    }

    /**
     * {@code TRAIT.095} and {@code TRAIT.096}, asserted over the list rather than over a world.
     *
     * <p>This is the assertion the phase order needs and a golden cannot give it. No marker in the
     * shipped pack carries two of the four traits, so inverting the order moves nothing measurable -
     * and the order <em>was</em> inverted for the length of this task, because this enum was written in
     * {@code 01-traits.md} §4's section order before {@code TRAIT.095} existed and was not brought into
     * line when it did.</p>
     *
     * <p>What inverting it costs, concretely: {@code Parts.handleBlockEntity} derives the block entity
     * type from the block it is handed and queues NBT for it. Run before the light, it queues against
     * the <em>lit</em> block, the light then swaps in its {@code unlit} replacement, and
     * {@code Parts.forgetBlockEntities} discards the orphaned data - silently. That would make
     * {@code TRAIT.044}'s accept case, a campfire whose unlit replacement is a barrel, a promise the
     * loader checks and the generator breaks.</p>
     */
    @Rule("TRAIT.095")
    @Rule("TRAIT.096")
    @Test
    void selectionIsAppliedBeforeDecorationSoNbtIsQueuedAgainstTheBlockThatSurvives() {
        Palette.Info info = Palette.Info.of(null, null, inPlaceLight(), NBT);

        assertEquals(List.of(MarkerTrait.LIGHT, MarkerTrait.BLOCK_ENTITY), info.applied());
        assertTrue(info.applied().indexOf(MarkerTrait.LIGHT)
                        < info.applied().indexOf(MarkerTrait.BLOCK_ENTITY),
                "TRAIT.096: a decoration trait applies to the state selection produced, so the light "
                        + "must have chosen the block before the NBT is attached to it");
    }

    @Test
    void everySelectionTraitPrecedesEveryDecorationTraitWhateverTheMarkerCarries() {
        List<MarkerTrait> all = Palette.Info.of("urbex:easymobs", "urbex:chest", inPlaceLight(), NBT)
                .applied();

        assertEquals(0, all.indexOf(MarkerTrait.LIGHT),
                "the only selection trait leads, and TRAIT.095 forbids anything preceding it: " + all);
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
