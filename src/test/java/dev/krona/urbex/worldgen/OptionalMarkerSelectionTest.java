package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.varia.DensitySelector;
import dev.krona.urbex.worldgen.lost.Transform;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real compiled traits reach the placement seam before rotation and block entity decoration. */
class OptionalMarkerSelectionTest {
    private static final long SEED = 216;
    private static final int Y = 80;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("TRAIT.060")
    @Rule("TRAIT.061")
    @Test
    void endpointsUseTheNamedPresetDensityEvenWithoutInfo() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": {
                  "L": { "block": "minecraft:stone", "traits": { "urbex:optional": {
                    "density": "lightingDensity", "replacement": "minecraft:gold_block" } } },
                  "O": { "block": "minecraft:stone", "traits": { "urbex:optional": {
                    "density": "lootDensity", "replacement": "minecraft:gold_block" } } }
                } }
                """);
        CompiledPalette.Placed original = palette.placedAt('L', SEED, 3, Y, 4);
        assertNull(original.info(), "an optional-only marker takes Parts' metadata-free path");
        assertFalse(palette.isSimple('L'), "a density marker cannot use one-state bulk filling");
        assertSame(original, original.selectOptional(preset(1, 0), SEED, 3, Y, 4));
        CompiledPalette.Placed rejected = original.selectOptional(preset(0, 1), SEED, 3, Y, 4);
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), rejected.state());
        assertSame(rejected, original.selectOptional(preset(0, 1), SEED, 3, Y, 4),
                "selection returns precompiled slots rather than allocating per placement");
        CompiledPalette.Placed loot = palette.placedAt('O', SEED, 3, Y, 4);
        assertSame(loot, loot.selectOptional(preset(0, 1), SEED, 3, Y, 4));
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(),
                loot.selectOptional(preset(1, 0), SEED, 3, Y, 4).state());
    }

    @Rule("TRAIT.062")
    @Test
    void rejectedDefaultAirClearsAnExistingSolidBlock() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "O": { "block": "minecraft:stone",
                  "traits": { "urbex:optional": { "density": "lightingDensity" } } } } }
                """);
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        driver.current(3, Y, 4).block(Blocks.GOLD_BLOCK.defaultBlockState());
        driver.current(3, Y, 4);
        CompiledPalette.Placed original = palette.placedAt('O', SEED, 3, Y, 4);
        CompiledPalette.Placed selected = original.selectOptional(preset(0, 0), SEED, 3, Y, 4);
        assertNotSame(original, selected, "a rejected marker differs from transparent authored air");
        Parts.writeMarker(driver, selected, Parts.transformMarker(selected, Transform.ROTATE_90), SEED);
        driver.actuallyGenerate(chunk);
        assertTrue(chunk.getBlockState(new BlockPos(3, Y, 4)).isAir());
    }

    @Rule("TRAIT.063")
    @Rule("TRAIT.065")
    @Test
    void optionalAndInPlaceLightSelectIdenticalWeightedReplacementsBeforeRotation() {
        String replacement = """
                { "kind": "weighted", "choices": [
                  { "share": 0.5, "block": "minecraft:oak_stairs[facing=north]" },
                  { "rest": true, "block": "minecraft:oak_stairs[facing=north]",
                    "traits": { "urbex:rotatable": false } } ] }
                """;
        CompiledPalette palette = palette("""
                { "version": 2, "palette": {
                  "O": { "block": "minecraft:lantern", "traits": { "urbex:optional": {
                    "density": "lightingDensity", "replacement": %s } } },
                  "L": { "block": "minecraft:lantern", "traits": { "urbex:light": { "unlit": %s } } }
                } }
                """.formatted(replacement, replacement));
        Preset preset = preset(.4f, 1);
        Set<BlockState> seen = new HashSet<>();
        for (int x = -16; x < 16; x++) {
            for (int z = -16; z < 16; z++) {
                CompiledPalette.Placed optional = palette.placedAt('O', SEED, x, Y, z)
                        .selectOptional(preset, SEED, x, Y, z);
                CompiledPalette.Placed light = palette.placedAt('L', SEED, x, Y, z)
                        .selectOptional(preset, SEED, x, Y, z);
                assertEquals(DensitySelector.lighting(SEED, new BlockPos(x, Y, z), .4f),
                        optional.state().is(Blocks.LANTERN));
                BlockState transformed = Parts.transformMarker(optional, Transform.ROTATE_90);
                assertEquals(transformed, Parts.transformMarker(light, Transform.ROTATE_90));
                seen.add(transformed);
                if (!light.state().is(Blocks.LANTERN)) {
                    assertNull(light.info(), "the rejected source light must not reselect its replacement");
                }
            }
        }
        assertEquals(Set.of(Blocks.LANTERN.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST)), seen);
        // Unrelated selections, in a different order, cannot advance a mutable random stream.
        CompiledPalette.Placed before = palette.placedAt('O', SEED, 1, Y, 2).selectOptional(preset, SEED, 1, Y, 2);
        for (int x = 20; x >= -20; x--) {
            palette.placedAt('L', SEED, x, Y, 5).selectOptional(preset, SEED, x, Y, 5);
        }
        assertSame(before, palette.placedAt('O', SEED, 1, Y, 2).selectOptional(preset, SEED, 1, Y, 2));
    }

    @Rule("TRAIT.007")
    @Rule("TRAIT.096")
    @Test
    void replacementsKeepOuterDecoratorsButNeverInheritRotationOrDamage() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": {
                  "O": { "block": "minecraft:chest", "traits": {
                    "urbex:rotatable": false,
                    "urbex:damaged": { "into": "minecraft:cobweb" },
                    "urbex:block_entity": { "nbt": { "Items": [] } },
                    "urbex:optional": { "density": "lightingDensity",
                      "replacement": "minecraft:barrel[facing=north]" } } },
                  "D": { "block": "minecraft:stone", "traits": { "urbex:optional": {
                    "density": "lightingDensity", "replacement": { "block": "minecraft:oak_stairs[facing=north]",
                    "traits": { "urbex:rotatable": false,
                      "urbex:damaged": { "into": "minecraft:bricks" } } } } } }
                } }
                """);
        CompiledPalette.Placed original = palette.placedAt('O', SEED, 3, Y, 4);
        CompiledPalette.Placed selected = original.selectOptional(preset(0, 0), SEED, 3, Y, 4);
        assertEquals(original.info().tag(), selected.info().tag());
        assertTrue(selected.rotatable(), "the satellite defaults to rotation regardless of its parent");
        assertEquals(Direction.EAST, Parts.transformMarker(selected, Transform.ROTATE_90).getValue(BarrelBlock.FACING));
        assertNull(selected.damagedAt(SEED, 3, Y, 4), "the parent's damage must not leak into its satellite");
        CompiledPalette.Placed own = palette.placedAt('D', SEED, 3, Y, 4).selectOptional(preset(0, 0), SEED, 3, Y, 4);
        assertEquals(Direction.NORTH, Parts.transformMarker(own, Transform.ROTATE_90).getValue(StairBlock.FACING));
        assertEquals(Blocks.BRICKS.defaultBlockState(), own.damagedAt(SEED, 3, Y, 4));
    }

    @Rule("TRAIT.009")
    @Test
    void nestedReplacementSelectionUsesTheReplacementDensity() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "O": { "block": "minecraft:stone", "traits": {
                  "urbex:optional": { "density": "lightingDensity", "replacement": {
                    "block": "minecraft:bricks", "traits": { "urbex:optional": {
                      "density": "lootDensity", "replacement": "minecraft:gold_block" } } } } } } } }
                """);
        CompiledPalette.Placed original = palette.placedAt('O', SEED, 3, Y, 4);
        assertEquals(Blocks.BRICKS.defaultBlockState(), original.selectOptional(preset(0, 1), SEED, 3, Y, 4).state());
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), original.selectOptional(preset(0, 0), SEED, 3, Y, 4).state());
    }

    @Rule("TRAIT.066")
    @Test
    void unknownDensityIsRejectedWithTheMarkerAndNamedDiagnostic() {
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(compile("""
                { "version": 2, "palette": { "O": { "block": "minecraft:stone",
                  "traits": { "urbex:optional": { "density": "stuff" } } } } }
                """, diagnostics).isEmpty());
        String error = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_027.matches(error), error);
        assertTrue(error.contains("marker 'O'"), error);
        assertTrue(error.contains("stuff"), error);
        assertTrue(error.contains("lightingDensity"), error);
    }

    private static Preset preset(float lighting, float loot) {
        PresetDraft draft = new PresetDraft(Identifier.parse("urbex:optional_test"));
        draft.LIGHTING_DENSITY = lighting;
        draft.LOOT_DENSITY = loot;
        return draft.resolve();
    }

    private static CompiledPalette palette(String json) {
        Diagnostics diagnostics = new Diagnostics();
        CompiledV2Palette compiled = compile(json, diagnostics)
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("compile failed")));
        return new CompiledPalette(Palette.version2(Identifier.parse("urbex:optional_test"), compiled));
    }

    private static java.util.Optional<CompiledV2Palette> compile(String json, Diagnostics diagnostics) {
        PaletteV2Definition definition = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        return NodeResolver.resolve(definition, diagnostics).flatMap(resolved -> CompiledV2Palette.compile(resolved,
                Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft")),
                TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()), "'urbex:optional_test'", diagnostics));
    }
}
