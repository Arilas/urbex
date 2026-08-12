package dev.krona.urbex.worldgen.lost.cityassets;

import com.mojang.serialization.Lifecycle;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A palette entry naming a {@code variant} resolves it against the registries the palette itself
 * came from (issue #60).
 * <p>
 * It used to resolve against {@code ServerAccess.getServer().getLevel(Level.OVERWORLD)} - the
 * process-wide server reference, and then unconditionally that server's overworld. Two things were
 * wrong with that, and only the second is visible in a single-dimension world: a palette compiled
 * for any other dimension took the overworld's variants, and a palette compiled on a worldgen
 * worker before the static server reference was populated threw a {@link NullPointerException} out
 * of asset compilation.
 */
class PaletteVariantResolutionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @AfterEach
    void clearRegistries() {
        // AssetRegistries.VARIANTS caches by id, and these tests deliberately register different
        // blocks under the same id in different registry accesses.
        AssetRegistries.reset();
    }

    @Test
    void aVariantResolvesAgainstTheRegistriesThePaletteCameFrom() {
        RegistryAccess access = variantsWith("minecraft:deepslate");

        Palette compiled = new Palette(access, List.of(paletteNamingVariant()));

        assertEquals(List.of("minecraft:deepslate"), blocksOf(compiled, 'V'));
    }

    /**
     * The bug, stated as a test. Two dimensions whose registries define {@code urbex:rubble}
     * differently must each get their own - the old code handed both the overworld's.
     */
    @Test
    void twoRegistryAccessesResolveTheSameVariantIdIndependently() {
        Palette fromFirst = new Palette(variantsWith("minecraft:deepslate"),
                List.of(paletteNamingVariant()));
        AssetRegistries.reset();
        Palette fromSecond = new Palette(variantsWith("minecraft:sandstone"),
                List.of(paletteNamingVariant()));

        assertEquals(List.of("minecraft:deepslate"), blocksOf(fromFirst, 'V'));
        assertEquals(List.of("minecraft:sandstone"), blocksOf(fromSecond, 'V'),
                "the second palette must not inherit the first's answer for the same variant id");
    }

    /**
     * Compiling a variant entry with no registry access to resolve it against is refused, naming
     * the variant. The old shape's answer to the same situation was a {@code NullPointerException}
     * from {@code server.getLevel(...)}, in asset compilation, on a worker thread.
     */
    @Test
    void aVariantEntryWithNoRegistryAccessFailsNamingTheVariant() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> new Palette(null, List.of(paletteNamingVariant())));

        String message = String.valueOf(rootCause(failure).getMessage());
        assertTrue(message.contains("urbex:rubble"), () -> "message must name the variant: " + message);
    }

    private static Throwable rootCause(Throwable t) {
        return t.getCause() == null ? t : rootCause(t.getCause());
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

    private static PaletteRE paletteNamingVariant() {
        PaletteEntry entry = new PaletteEntry("V", Optional.empty(), Optional.of("urbex:rubble"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new PaletteRE(Optional.empty(), Optional.of(List.of(entry)))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "variant-palette"));
    }

    /** A registry access whose only {@code urbex:rubble} variant is one weighted block. */
    private static RegistryAccess variantsWith(String block) {
        MappedRegistry<VariantRE> variants = new MappedRegistry<>(
                CustomRegistries.VARIANTS_REGISTRY_KEY, Lifecycle.stable());
        variants.register(
                ResourceKey.create(CustomRegistries.VARIANTS_REGISTRY_KEY,
                        Identifier.fromNamespaceAndPath("urbex", "rubble")),
                new VariantRE(Optional.empty(),
                        Optional.of(new Mergeable<>(true, List.of(new BlockEntry(1, block))))),
                RegistrationInfo.BUILT_IN);
        return new RegistryAccess.ImmutableRegistryAccess(List.<Registry<?>>of(variants.freeze())).freeze();
    }
}
