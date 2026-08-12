package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.VariantDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A palette entry naming a {@code variant} resolves it against the variants it was handed (issue
 * #60), which after issue #128 means the ones its own world's compiler had already built.
 * <p>
 * It used to resolve against {@code ServerAccess.getServer().getLevel(Level.OVERWORLD)} - the
 * process-wide server reference, and then unconditionally that server's overworld. Two things were
 * wrong with that, and only the second is visible in a single-dimension world: a palette compiled
 * for any other dimension took the overworld's variants, and a palette compiled on a worldgen worker
 * before the static server reference was populated threw a {@link NullPointerException} out of asset
 * compilation.
 * <p>
 * {@link AssetCompilerTest} covers the same resolution through the compiler, which is how production
 * reaches it. This covers the seam itself, including the case the compiler cannot produce.
 */
class PaletteVariantResolutionTest {

    private static final Identifier PALETTE_ID = Identifier.fromNamespaceAndPath("urbex", "variant-palette");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aVariantResolvesAgainstTheIndexThePaletteWasGiven() {
        Palette compiled = new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, variantsWith("minecraft:deepslate"),
                List.of(paletteNamingVariant()));

        assertEquals(List.of("minecraft:deepslate"), blocksOf(compiled, 'V'));
    }

    /**
     * The bug, stated as a test. Two worlds whose packs define {@code urbex:rubble} differently must
     * each get their own - the old code handed both the overworld's.
     */
    @Test
    void twoIndexesResolveTheSameVariantIdIndependently() {
        Palette fromFirst = new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, variantsWith("minecraft:deepslate"),
                List.of(paletteNamingVariant()));
        Palette fromSecond = new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, variantsWith("minecraft:sandstone"),
                List.of(paletteNamingVariant()));

        assertEquals(List.of("minecraft:deepslate"), blocksOf(fromFirst, 'V'));
        assertEquals(List.of("minecraft:sandstone"), blocksOf(fromSecond, 'V'),
                "the second palette must not inherit the first's answer for the same variant id");
    }

    /**
     * Compiling a variant entry with no variants to resolve it against is refused, naming the
     * palette, the marker and the variant. The old shape's answer to the same situation was a
     * {@code NullPointerException} from {@code server.getLevel(...)}, in asset compilation, on a
     * worker thread.
     */
    @Test
    void aVariantEntryWithNoVariantIndexFailsNamingWhatItWanted() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(paletteNamingVariant())));

        assertTrue(failure.getMessage().contains("urbex:rubble"), failure.getMessage());
        assertTrue(failure.getMessage().contains("variant-palette"), failure.getMessage());
    }

    private static List<String> blocksOf(Palette palette, char marker) {
        Object blocks = palette.getPalette().get(marker).blocks();
        if (blocks instanceof BlockState single) {
            return List.of(nameOf(single));
        }
        @SuppressWarnings("unchecked")
        Pair<Integer, BlockState>[] weighted = (Pair<Integer, BlockState>[]) blocks;
        return java.util.Arrays.stream(weighted).map(pair -> nameOf(pair.getRight())).distinct().toList();
    }

    private static String nameOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static PaletteDefinition paletteNamingVariant() {
        PaletteEntry entry = new PaletteEntry("V", Optional.empty(), Optional.of("urbex:rubble"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new PaletteDefinition(Optional.empty(), Optional.of(List.of(entry)));
    }

    /** An index whose only {@code urbex:rubble} variant is one weighted block. */
    private static AssetIndex<Variant> variantsWith(String block) {
        Identifier id = Identifier.fromNamespaceAndPath("urbex", "rubble");
        VariantDefinition definition = new VariantDefinition(Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of(new BlockEntry(1, block)))));
        return new AssetIndex<>("urbex:variants", Map.of(id, new Variant(id, BuiltInRegistries.BLOCK, List.of(definition))));
    }
}
