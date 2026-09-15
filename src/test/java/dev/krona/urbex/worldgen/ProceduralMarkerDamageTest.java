package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.Transform;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Sampling a support material once must not sample its eventual damage at that same anchor. */
class ProceduralMarkerDamageTest {
    private static final long SEED = 216;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("TRAIT.011")
    @Test
    void repeatedPrimaryMaterialsKeepTheirAnchorChoiceButDamageIsChosenAtEachWrittenPosition() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "P": { "kind": "weighted", "choices": [
                  { "share": 0.5, "block": "minecraft:stone" },
                  { "rest": true, "block": "minecraft:gold_block" } ], "traits": {
                  "urbex:damaged": { "into": { "kind": "weighted", "choices": [
                    { "share": 0.5, "block": "minecraft:bricks" },
                    { "rest": true, "block": "minecraft:cobweb" } ] } } } } } }
                """);
        Preset preset = preset();
        ProtoChunk chunk = TestChunk.emptyChunk(new ChunkPos(7, -11));
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        CompiledPalette.Placed material = palette.placedAt('P', SEED, 112, 80, -176);
        Set<BlockState> primaryIfResampled = new HashSet<>();
        Set<BlockState> damageOutcomes = new HashSet<>();
        for (int method = 0; method < 3; method++) {
            int x = 2 + method;
            if (method == 2) {
                // Door frames and support columns write a whole range from one sampled material.
                driver.setBlockRange(x, 64, 4, 96, material);
            } else if (method == 1) {
                // Corridor roofs reuse one material over successive add calls.
                driver.current(x, 64, 4);
                for (int y = 64; y < 96; y++) {
                    driver.add(material);
                }
                assertEquals(96, driver.getY());
            } else {
                for (int y = 64; y < 96; y++) {
                    driver.current(x, y, 4).block(material);
                }
            }
            driver.flushToChunk(chunk);
            for (int y = 64; y < 96; y++) {
                driver.current(x, y, 4);
                int absoluteX = 112 + x;
                int absoluteZ = -172;
                primaryIfResampled.add(palette.placedAt('P', SEED, absoluteX, y, absoluteZ).state());
                assertEquals(material.state(), chunk.getBlockState(new BlockPos(absoluteX, y, absoluteZ)),
                        "migrating damage must not change the existing primary-material sampling scope");
                BlockState expected = Rng.indexAtPos(SEED, absoluteX, y, absoluteZ,
                        Rng.Purpose.DAMAGE_REPLACEMENT, 128) < 64
                        ? Blocks.BRICKS.defaultBlockState() : Blocks.COBWEB.defaultBlockState();
                CompiledPalette.Placed damaged = driver.damageHere(preset, SEED);
                assertNotNull(damaged);
                assertEquals(expected, damaged.state(), "damage must use this destination, not the material anchor");
                damageOutcomes.add(damaged.state());
            }
        }
        assertEquals(2, primaryIfResampled.size(), "per-position primary sampling would visibly change this fixture");
        assertEquals(Set.of(Blocks.BRICKS.defaultBlockState(), Blocks.COBWEB.defaultBlockState()), damageOutcomes);
    }

    @Rule("TRAIT.011")
    @Rule("TRAIT.070")
    @Test
    void aProcedurallyAdjustedStateRetainsItsSelectedMarkersTransformedDamage() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": { "P": { "block": "minecraft:oak_stairs[facing=north]",
                  "traits": { "urbex:damaged": { "into": "minecraft:cobblestone_stairs[facing=north]" } } } } }
                """);
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        CompiledPalette.Placed selected = palette.placedAt('P', SEED, 2, 80, 4).transformed(Transform.ROTATE_90);
        BlockState actual = selected.state().setValue(StairBlock.HALF, Half.TOP);
        driver.current(2, 80, 4).block(actual, selected);
        driver.current(3, 80, 4).add(actual, selected);
        assertEquals(81, driver.getY());
        driver.flushToChunk(chunk);
        for (int x = 2; x <= 3; x++) {
            driver.current(x, 80, 4);
            assertEquals(actual, driver.getBlock(), "the explicit placement state must reach the chunk");
            assertEquals(Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
                    driver.damageHere(preset(), SEED).state());
        }
    }

    private static Preset preset() {
        return new PresetDraft(Identifier.parse("urbex:procedural_damage_test")).resolve();
    }

    private static CompiledPalette palette(String json) {
        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition definition = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        CompiledV2Palette compiled = NodeResolver.resolve(definition, diagnostics)
                .flatMap(resolved -> CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft")),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                        "'urbex:procedural_damage_test'", diagnostics))
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("compile failed")));
        return new CompiledPalette(Palette.version2(Identifier.parse("urbex:procedural_damage_test"), compiled));
    }
}
