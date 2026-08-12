package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A palette is a keyed collection, so its {@code extends} chain merges by character rather than by
 * position: a child repainting two markers out of thirty keeps the other twenty-eight.
 */
class PaletteExtendsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void childOverridesOnlyTheCharactersItDeclares() {
        PaletteRE parent = palette("parent",
                entry('S', "minecraft:stone"),
                entry('b', "minecraft:bricks"),
                entry('g', "minecraft:glass"));
        PaletteRE child = palette("child", entry('S', "minecraft:deepslate"));

        Palette resolved = new Palette(BuiltInRegistries.BLOCK, null, List.of(parent, child));

        assertEquals("minecraft:deepslate", blockOf(resolved, 'S'), "the child's character wins");
        assertEquals("minecraft:bricks", blockOf(resolved, 'b'), "characters it never mentions survive");
        assertEquals("minecraft:glass", blockOf(resolved, 'g'));
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "child"), resolved.getId(),
                "the resolved palette is named after the leaf, not the root");
    }

    @Test
    void aCharacterTheChildAddsJoinsTheOnesItInherits() {
        PaletteRE parent = palette("parent", entry('S', "minecraft:stone"));
        PaletteRE child = palette("child", entry('w', "minecraft:oak_planks"));

        Palette resolved = new Palette(BuiltInRegistries.BLOCK, null, List.of(parent, child));

        assertEquals(2, resolved.getPalette().size());
        assertEquals("minecraft:stone", blockOf(resolved, 'S'));
        assertEquals("minecraft:oak_planks", blockOf(resolved, 'w'));
    }

    @Test
    void theLastEntryInADeepChainWins() {
        PaletteRE root = palette("root", entry('S', "minecraft:stone"));
        PaletteRE middle = palette("middle", entry('S', "minecraft:andesite"));
        PaletteRE leaf = palette("leaf", entry('S', "minecraft:deepslate"));

        assertEquals("minecraft:deepslate", blockOf(new Palette(BuiltInRegistries.BLOCK, null, List.of(root, middle, leaf)), 'S'));
        assertEquals("minecraft:andesite", blockOf(new Palette(BuiltInRegistries.BLOCK, null, List.of(root, middle)), 'S'));
    }

    @Test
    void anOverriddenCharacterTakesItsDamagedMappingWithIt() {
        // The parent's 'S' is replaced wholesale, so its damaged-state mapping must go with it
        // rather than linger keyed on a block the resolved palette no longer places.
        PaletteRE parent = palette("parent", damagedEntry('S', "minecraft:stone", "minecraft:cobblestone"));
        PaletteRE child = palette("child", entry('S', "minecraft:deepslate"));

        Palette resolved = new Palette(BuiltInRegistries.BLOCK, null, List.of(parent, child));

        assertNull(resolved.getDamaged().get(state("minecraft:stone")),
                "the replaced parent entry must not leave its damaged mapping behind");
        assertEquals(1, resolved.getPalette().size());

        // ... and a mapping on a character nobody overrides is still there.
        Palette parentOnly = new Palette(BuiltInRegistries.BLOCK, null, List.of(parent));
        assertNotNull(parentOnly.getDamaged().get(state("minecraft:stone")));
    }

    private static BlockState state(String block) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(block)).defaultBlockState();
    }

    private static String blockOf(Palette palette, char marker) {
        Palette.PE pe = palette.getPalette().get(marker);
        assertNotNull(pe, () -> "No palette entry for marker '" + marker + "'");
        BlockState blockState = (BlockState) pe.blocks();
        return BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
    }

    private static PaletteRE palette(String path, PaletteEntry... entries) {
        return new PaletteRE(Optional.empty(), Optional.of(List.of(entries)))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    private static PaletteEntry entry(char marker, String block) {
        return new PaletteEntry(Character.toString(marker), Optional.of(block),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PaletteEntry damagedEntry(char marker, String block, String damaged) {
        return new PaletteEntry(Character.toString(marker), Optional.of(block),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(damaged),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
