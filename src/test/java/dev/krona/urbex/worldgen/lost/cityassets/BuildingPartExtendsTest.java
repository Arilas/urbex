package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.PartMeta;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A part's geometry comes from the last entry in its {@code extends} chain that declares it, so
 * "the radio tower, repainted" is a file with an {@code extends} and a {@code refpalette} in it.
 */
class BuildingPartExtendsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 2x2 footprint, two levels. */
    private static final List<List<String>> TOWER = List.of(
            List.of("ab", "cd"),
            List.of("ef", "gh"));

    @Test
    void aPartThatOnlySwapsItsPaletteKeepsItsAncestorsGeometry() {
        BuildingPartDefinition parent = part("radiotower", geometry(2, 2, TOWER)
                .refpalette("urbex:radiotower"));
        BuildingPartDefinition child = part("radiotower_rusted", inherits("urbex:radiotower")
                .refpalette("urbexmt:radiotower_rusted"));

        BuildingPart resolved = new BuildingPart(TestAssetId.of("radiotower_rusted"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, child));

        assertEquals(2, resolved.getXSize());
        assertEquals(2, resolved.getZSize());
        assertEquals(2, resolved.getSliceCount());
        assertArrayEquals(new String[]{"abcd", "efgh"}, resolved.getSlices());
        assertEquals("urbexmt:radiotower_rusted", resolved.getRefPaletteName(),
                "the child's own refpalette still wins");
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "radiotower_rusted"), resolved.getId());
    }

    @Test
    void declaringSlicesReplacesTheInheritedOnesWholesale() {
        BuildingPartDefinition parent = part("tower", geometry(2, 2, TOWER));
        BuildingPartDefinition child = part("tower_short", inherits("urbex:tower")
                .slices(List.of(List.of("ij", "kl"))));

        BuildingPart resolved = new BuildingPart(TestAssetId.of("tower_short"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, child));

        assertEquals(1, resolved.getSliceCount());
        assertArrayEquals(new String[]{"ijkl"}, resolved.getSlices());
        assertEquals(2, resolved.getXSize(), "dimensions are still inherited");
        assertEquals(2, resolved.getZSize());
    }

    @Test
    void anXSizeThatContradictsInheritedSlicesIsALoadError() {
        BuildingPartDefinition parent = part("tower", geometry(2, 2, TOWER));
        BuildingPartDefinition child = part("tower_wide", inherits("urbex:tower").xsize(3));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(TestAssetId.of("tower_wide"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, child)));

        assertTrue(error.getMessage().contains("urbex:tower_wide"),
                () -> "the error must name the part: " + error.getMessage());
        assertTrue(error.getMessage().contains("3"),
                () -> "the error must name the declared size: " + error.getMessage());
        assertTrue(error.getMessage().contains("2 wide"),
                () -> "the error must name the actual width: " + error.getMessage());
    }

    @Test
    void aZSizeThatContradictsInheritedSlicesIsALoadError() {
        BuildingPartDefinition parent = part("tower", geometry(2, 2, TOWER));
        BuildingPartDefinition child = part("tower_deep", inherits("urbex:tower").zsize(5));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(TestAssetId.of("tower_deep"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, child)));

        assertTrue(error.getMessage().contains("urbex:tower_deep"),
                () -> "the error must name the part: " + error.getMessage());
    }

    @Test
    void aChainThatNeverDeclaresSlicesIsALoadError() {
        BuildingPartDefinition parent = part("abstract_tower", new Builder().xsize(2).zsize(2));
        BuildingPartDefinition child = part("tower_rusted", inherits("urbex:abstract_tower")
                .refpalette("urbex:rusted"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(TestAssetId.of("tower_rusted"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, child)));

        assertTrue(error.getMessage().contains("urbex:tower_rusted"),
                () -> "the error must name the part that failed to resolve: " + error.getMessage());
        assertTrue(error.getMessage().contains("slices"),
                () -> "the error must say what is missing: " + error.getMessage());
    }

    @Test
    void aChainThatNeverDeclaresDimensionsIsALoadError() {
        BuildingPartDefinition parent = part("sliced_only", new Builder().slices(TOWER));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(TestAssetId.of("sliced_only"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent)));

        assertTrue(error.getMessage().contains("urbex:sliced_only"), error::getMessage);
    }

    @Test
    void metadataFromTheChildReplacesTheParentsUnlessItOptsIntoAppending() {
        BuildingPartDefinition parent = part("tower", geometry(2, 2, TOWER).meta(true, meta("support")));
        BuildingPartDefinition replacing = part("tower_a", inherits("urbex:tower").meta(true, meta("nowater")));
        BuildingPartDefinition appending = part("tower_b", inherits("urbex:tower").meta(false, meta("nowater")));

        BuildingPart replaced = new BuildingPart(TestAssetId.of("tower_b"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, replacing));
        assertTrue(replaced.getMetaBoolean("nowater"));
        assertFalse(replaced.getMetaBoolean("support"), "a bare array replaces");

        BuildingPart appended = new BuildingPart(TestAssetId.of("tower_b"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, appending));
        assertTrue(appended.getMetaBoolean("nowater"));
        assertTrue(appended.getMetaBoolean("support"), "{\"replace\": false} keeps the inherited meta");
    }

    @Test
    void anInlinePaletteMergesByCharacterAlongTheChainJustLikeARegisteredOne() {
        // An inline palette is a keyed collection too. Replacing it wholesale would reproduce, one
        // level down, exactly the failure the keyed-collection rule exists to prevent: a child
        // repainting one marker would silently lose the other two.
        BuildingPartDefinition parent = part("tower", geometry(2, 2, TOWER)
                .inlinePalette(entry('a', "minecraft:stone"),
                        entry('b', "minecraft:bricks"),
                        entry('c', "minecraft:glass")));
        BuildingPartDefinition child = part("tower_rusted", inherits("urbex:tower")
                .inlinePalette(entry('a', "minecraft:deepslate")));

        Palette resolved = new BuildingPart(TestAssetId.of("tower_rusted"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, child)).getLocalPalette();

        assertEquals(3, resolved.getPalette().size(),
                "the two characters the child never mentions must survive");
        assertEquals("minecraft:deepslate", blockOf(resolved, 'a'), "the child's character wins");
        assertEquals("minecraft:bricks", blockOf(resolved, 'b'));
        assertEquals("minecraft:glass", blockOf(resolved, 'c'));
    }

    @Test
    void namingAPaletteWithRefpaletteDropsAnInheritedInlineOne() {
        // refpalette and an inline block are two ways to say the same thing, not two layers.
        BuildingPartDefinition parent = part("tower", geometry(2, 2, TOWER)
                .inlinePalette(entry('a', "minecraft:stone")));
        BuildingPartDefinition child = part("tower_ref", inherits("urbex:tower")
                .refpalette("urbex:somewhere_else"));

        // The refpalette wins, and the inherited inline palette is gone - so the part cannot be
        // built at all unless the named palette exists. That is the compile-time resolution doing its
        // job: this used to build fine and blow up on the first chunk that asked for the palette.
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> new BuildingPart(TestAssetId.of("tower_ref"), BuiltInRegistries.BLOCK, PALETTES, List.of(parent, child)),
                "an ancestor's inline palette must not survive the child naming a refpalette");
        assertTrue(failure.getMessage().contains("urbex:somewhere_else"), failure.getMessage());
    }

    @Test
    void anExtendsInsideAnInlinePaletteIsRejectedRatherThanIgnored() {
        // PaletteDefinition.CODEC accepts "extends" wherever it is embedded, but an inline block is not a
        // registry entry, so nothing can resolve it. Silently dropping it is the one option that
        // lets a datapack mean something other than what it says.
        BuildingPartDefinition part = part("tower", geometry(2, 2, TOWER)
                .inlinePalette(Optional.of(Identifier.parse("urbex:common")),
                        entry('a', "minecraft:stone")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new BuildingPart(TestAssetId.of("tower"), BuiltInRegistries.BLOCK, PALETTES, List.of(part)));

        assertTrue(error.getMessage().contains("urbex:tower"), error::getMessage);
        assertTrue(error.getMessage().contains("urbex:common"), error::getMessage);
        assertTrue(error.getMessage().contains("refpalette"), error::getMessage);
    }

    private static String blockOf(Palette palette, char marker) {
        BlockState state = (BlockState) palette.getPalette().get(marker).blocks();
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static PaletteEntry entry(char marker, String block) {
        return new PaletteEntry(Character.toString(marker), Optional.of(block),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static PartMeta meta(String key) {
        return new PartMeta(key, Boolean.TRUE, null, null, null, null);
    }

    private static Builder geometry(int xSize, int zSize, List<List<String>> slices) {
        return new Builder().xsize(xSize).zsize(zSize).slices(slices);
    }

    private static Builder inherits(String id) {
        return new Builder().extendsId(id);
    }

    /**
     * The palettes these parts' {@code refpalette} references resolve against. Needed because a
     * refpalette is resolved when the part is compiled now, not when a chunk first asks for it
     * (issue #128) - so a part naming one cannot be built without it.
     */
    private static final AssetIndex<Palette> PALETTES = new AssetIndex<>("urbex:palettes", Map.of(
            Identifier.fromNamespaceAndPath("urbex", "radiotower"), new Palette("radiotower"),
            Identifier.fromNamespaceAndPath("urbexmt", "radiotower_rusted"), new Palette("radiotower_rusted"),
            Identifier.fromNamespaceAndPath("urbex", "rusted"), new Palette("rusted")));

    /**
     * {@code path} is the id the part <em>would</em> be registered under. A decoded definition no
     * longer carries one (issue #128), so it is not written here - it is passed to
     * {@link BuildingPart}'s constructor at each call site, which is where the compiler passes it.
     */
    private static BuildingPartDefinition part(String path, Builder builder) {
        return builder.build();
    }

    private static final class Builder {
        private Optional<Identifier> extendsId = Optional.empty();
        private Optional<Integer> xSize = Optional.empty();
        private Optional<Integer> zSize = Optional.empty();
        private Optional<List<List<String>>> slices = Optional.empty();
        private Optional<String> refpalette = Optional.empty();
        private Optional<PaletteAssetDefinition> inlinePalette = Optional.empty();
        private Optional<Mergeable<PartMeta>> meta = Optional.empty();

        Builder extendsId(String id) {
            this.extendsId = Optional.of(Identifier.parse(id));
            return this;
        }

        Builder xsize(int x) {
            this.xSize = Optional.of(x);
            return this;
        }

        Builder zsize(int z) {
            this.zSize = Optional.of(z);
            return this;
        }

        Builder slices(List<List<String>> slices) {
            this.slices = Optional.of(slices);
            return this;
        }

        Builder refpalette(String name) {
            this.refpalette = Optional.of(name);
            return this;
        }

        Builder inlinePalette(PaletteEntry... entries) {
            return inlinePalette(Optional.empty(), entries);
        }

        Builder inlinePalette(Optional<Identifier> paletteExtends, PaletteEntry... entries) {
            this.inlinePalette = Optional.of(
                    new PaletteDefinition(paletteExtends, Optional.of(List.of(entries))));
            return this;
        }

        Builder meta(boolean replace, PartMeta... values) {
            this.meta = Optional.of(new Mergeable<>(replace, List.of(values)));
            return this;
        }

        BuildingPartDefinition build() {
            return new BuildingPartDefinition(extendsId, xSize, zSize, slices, refpalette, inlinePalette, meta);
        }
    }
}
