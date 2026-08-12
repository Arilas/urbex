package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack that names a block this game does not have generates the rest of itself (issue #91).
 * <p>
 * Two failure modes were in play before this. A block string carrying properties threw
 * {@code CommandSyntaxException} out of the parser, and since #128 moved compilation to load time
 * that refused the <em>world</em> rather than one chunk. A plain id silently became air — at its
 * full weight, so a variant meant as "mostly mossy cobble, occasionally somemod:fancy_moss" put
 * holes wherever the missing entry won its share of the draw.
 * <p>
 * Both are now the same answer: the entry drops out of its weighted list and the blocks that remain
 * take over its share. Re-normalisation needs no arithmetic here — {@code CompiledPalette} apportions
 * the character's 128 slots over whatever weights it is given, so removing an entry <em>is</em>
 * re-normalising. A character left with nothing at all generates as air, because a character the
 * palette does not map throws from the driver on the first part that uses it.
 * <p>
 * The line is drawn at the block id: see {@code BlockResolutionTest} for why a bad property on a
 * block this game <em>does</em> have is still a load error.
 */
class MissingBlocksAreSkippedTest {

    private static final String ABSENT = "somemod:no_such_block";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aVariantKeepsTheBlocksThisGameHas() {
        Variant variant = new Variant(BuiltInRegistries.BLOCK, List.of(variantOf(
                new BlockEntry(3, "minecraft:mossy_cobblestone"),
                new BlockEntry(1, ABSENT),
                new BlockEntry(2, "minecraft:stone"))));

        assertEquals(List.of(
                        Pair.of(3, Blocks.MOSSY_COBBLESTONE.defaultBlockState()),
                        Pair.of(2, Blocks.STONE.defaultBlockState())),
                variant.getBlocks(),
                "the absent entry is gone and the survivors keep their authored weights - the "
                        + "draw is over their total, so they have absorbed its share");
    }

    @Test
    void aWeightedPaletteEntryKeepsTheBlocksThisGameHas() {
        Palette palette = new Palette(BuiltInRegistries.BLOCK, null, List.of(paletteOf(
                weighted('r', new BlockEntry(1, ABSENT), new BlockEntry(1, "minecraft:gravel")))));

        Pair<Integer, BlockState>[] blocks = weightedBlocksOf(palette, 'r');
        assertEquals(1, blocks.length);
        assertSame(Blocks.GRAVEL.defaultBlockState(), blocks[0].getRight());
    }

    /**
     * The property-carrying form, which is the half of #91 that used to be a crash rather than a
     * silent hole.
     */
    @Test
    void aPropertyCarryingEntryFromAMissingModIsSkippedRatherThanThrowing() {
        Palette palette = new Palette(BuiltInRegistries.BLOCK, null, List.of(paletteOf(
                weighted('r', new BlockEntry(1, ABSENT + "[facing=north]"),
                        new BlockEntry(1, "minecraft:gravel")))));

        Pair<Integer, BlockState>[] blocks = weightedBlocksOf(palette, 'r');
        assertEquals(1, blocks.length);
        assertSame(Blocks.GRAVEL.defaultBlockState(), blocks[0].getRight());
    }

    @Test
    void aCharacterLeftWithNothingGeneratesAsAir() {
        Palette palette = new Palette(BuiltInRegistries.BLOCK, null, List.of(paletteOf(
                weighted('r', new BlockEntry(1, ABSENT), new BlockEntry(1, "othermod:also_absent")))));

        Palette.PE entry = palette.getPalette().get('r');
        assertNotNull(entry, "the character still has to map to something: one the palette does not "
                + "map throws from the driver on the first part that uses it");
        assertSame(Blocks.AIR.defaultBlockState(), entry.blocks());
    }

    /** A variant whose every block is absent reaches the palette as an empty list, not as a crash. */
    @Test
    void aVariantWithNothingLeftReachesThePaletteAsAir() {
        Variant emptied = new Variant(BuiltInRegistries.BLOCK,
                List.of(variantOf(new BlockEntry(1, ABSENT))));
        assertTrue(emptied.getBlocks().isEmpty());

        AssetIndex<Variant> variants = new AssetIndex<>("urbex:variants",
                Map.of(Identifier.fromNamespaceAndPath("urbex", "gone"), emptied));
        Palette palette = new Palette(BuiltInRegistries.BLOCK, variants, List.of(paletteOf(
                namingVariant('v', "urbex:gone"))));

        assertSame(Blocks.AIR.defaultBlockState(), palette.getPalette().get('v').blocks());
    }

    /**
     * A single {@code block} has no list to fall back on, so it is air — as it always was, except
     * that the property-carrying form used to throw instead.
     */
    @Test
    void aSingleBlockThatIsAbsentIsAir() {
        Palette palette = new Palette(BuiltInRegistries.BLOCK, null, List.of(paletteOf(
                single('x', ABSENT), single('y', ABSENT + "[facing=north]"))));

        assertSame(Blocks.AIR.defaultBlockState(), palette.getPalette().get('x').blocks());
        assertSame(Blocks.AIR.defaultBlockState(), palette.getPalette().get('y').blocks());
    }

    /**
     * An absent {@code damaged} block leaves no mapping at all rather than mapping to air. Air would
     * say "damaging this block deletes it", which is a claim the author never made.
     */
    @Test
    void anAbsentDamagedBlockLeavesNoMapping() {
        Palette palette = new Palette(BuiltInRegistries.BLOCK, null, List.of(paletteOf(
                damaged('S', "minecraft:stone", ABSENT))));

        assertTrue(palette.getDamaged().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Pair<Integer, BlockState>[] weightedBlocksOf(Palette palette, char marker) {
        Palette.PE entry = palette.getPalette().get(marker);
        assertNotNull(entry, () -> "No palette entry for marker '" + marker + "'");
        return (Pair<Integer, BlockState>[]) entry.blocks();
    }

    private static VariantRE variantOf(BlockEntry... blocks) {
        return new VariantRE(Optional.empty(), Optional.of(new Mergeable<>(true, List.of(blocks))))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "test_variant"));
    }

    private static PaletteRE paletteOf(PaletteEntry... entries) {
        return new PaletteRE(Optional.empty(), Optional.of(List.of(entries)))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "test_palette"));
    }

    private static PaletteEntry single(char marker, String block) {
        return new PaletteEntry(Character.toString(marker), Optional.of(block),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PaletteEntry damaged(char marker, String block, String damagedBlock) {
        return new PaletteEntry(Character.toString(marker), Optional.of(block),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(damagedBlock),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PaletteEntry weighted(char marker, BlockEntry... blocks) {
        return new PaletteEntry(Character.toString(marker), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(List.of(blocks)), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PaletteEntry namingVariant(char marker, String variant) {
        return new PaletteEntry(Character.toString(marker), Optional.empty(),
                Optional.of(variant), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
