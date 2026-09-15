package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.DamageArea;
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
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compile a real palette, write through Parts' final placement seam, damage, then commit a chunk. */
class MarkerDamageTest {
    private static final long SEED = 216;
    private static final int Y = 80;
    private static final String PALETTE = """
            { "version": 2, "palette": {
              "X": { "block": "minecraft:stone", "traits": {
                "urbex:damaged": { "into": "minecraft:bricks" } } },
              "Y": { "block": "minecraft:stone", "traits": {
                "urbex:damaged": { "into": "minecraft:cobweb" } } },
              "N": "minecraft:stone",
              "M": { "block": "minecraft:stone", "traits": {
                "urbex:damaged": { "into": "missing:unavailable" } } },
              "W": { "kind": "weighted", "choices": [
                { "share": 0.5, "block": "minecraft:stone", "traits": {
                  "urbex:damaged": { "into": "minecraft:bricks" } } },
                { "rest": true, "block": "minecraft:stone", "traits": {
                  "urbex:damaged": { "into": "minecraft:cobweb" } } } ] },
              "R": { "block": "minecraft:stone", "traits": {
                "urbex:damaged": { "into": { "kind": "weighted", "choices": [
                  { "share": 0.5, "block": "minecraft:bricks" },
                  { "rest": true, "block": "minecraft:cobweb" } ] } } } }
            } }
            """;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("TRAIT.010")
    @Rule("TRAIT.011")
    @Test
    void twoMarkersSharingOneStateKeepTheirOwnDamageThroughPartPlacementAndTheDamagePass() {
        CompiledPalette palette = palette(PALETTE);
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = driver(chunk);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                place(driver, palette, (x & 1) == 0 ? 'X' : 'Y', x, z);
            }
        }
        // A part2 floor flushes midway through generation: marker identity must outlive the buffer.
        driver.flushToChunk(chunk);
        int bricks = 0;
        int cobwebs = 0;
        TagSnapshot tags = TagSnapshot.capture();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                driver.current(x, Y, z);
                BlockState target = (x & 1) == 0 ? Blocks.BRICKS.defaultBlockState()
                        : Blocks.COBWEB.defaultBlockState();
                BlockState damaged = DamageArea.applyDamage(SEED, driver.getBlock(), tags, x, Y, z,
                        .6f, driver.damageHere(palette), 63, Blocks.WATER.defaultBlockState());
                if (Rng.floatAtPos(SEED, x, Y, z, Rng.Purpose.DAMAGE) <= .6f
                        && Rng.floatAtPos(SEED, x, Y, z, Rng.Purpose.DAMAGE_VARIANT) < .7f) {
                    assertEquals(target, damaged, "the original marker, not the state map, owns this damage");
                    if ((x & 1) == 0) bricks++; else cobwebs++;
                }
                driver.block(damaged);
            }
        }
        assertTrue(bricks > 20 && cobwebs > 20, "both marker kinds must actually reach damage");
        driver.actuallyGenerate(chunk);
        int committedBricks = 0;
        int committedCobwebs = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockState state = chunk.getBlockState(new BlockPos(x, Y, z));
                if (state.is(Blocks.BRICKS)) committedBricks++;
                if (state.is(Blocks.COBWEB)) committedCobwebs++;
            }
        }
        assertEquals(bricks, committedBricks);
        assertEquals(cobwebs, committedCobwebs);
    }

    @Rule("LOAD.021")
    @Rule("TRAIT.009")
    @Test
    void weightedMarkerSlotsAndWeightedDamageSatellitesRetainBothOutcomes() {
        CompiledPalette palette = palette(PALETTE);
        for (char marker : new char[]{'W', 'R'}) {
            ChunkDriver driver = driver(TestChunk.emptyChunk());
            Set<BlockState> seen = new HashSet<>();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    place(driver, palette, marker, x, z);
                    driver.current(x, Y, z);
                    seen.add(driver.damageHere(palette));
                }
            }
            assertEquals(Set.of(Blocks.BRICKS.defaultBlockState(), Blocks.COBWEB.defaultBlockState()), seen,
                    "marker " + marker + " must preserve both distinct damage sources");
        }
    }

    @Rule("TRAIT.012")
    @Test
    void aTraitlessOrUnavailableTargetMarkerSuppressesAnotherMarkersStateFallback() {
        CompiledPalette palette = palette(PALETTE);
        assertNotNull(palette.canBeDamagedToIronBars(Blocks.STONE.defaultBlockState()),
                "the fallback must be populated or this regression proves nothing");
        for (char marker : new char[]{'N', 'M'}) {
            ChunkDriver driver = driver(TestChunk.emptyChunk());
            place(driver, palette, 'X', 3, 4);
            place(driver, palette, marker, 3, 4);
            driver.current(3, Y, 4);
            assertNull(driver.damageHere(palette), "the final marker has no damaged form");
        }
    }

    @Test
    void aLocalPaletteKeepsItsDamageEvenWhenTheDamagePassOnlyKnowsTheChunkPalette() {
        CompiledPalette base = palette(PALETTE);
        Palette local = compiled("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone", "traits": {
                    "urbex:damaged": { "into": "minecraft:gold_block" } } } } }
                """);
        CompiledPalette merged = new CompiledPalette(base, local);
        ChunkDriver driver = driver(TestChunk.emptyChunk());
        place(driver, merged, 'X', 2, 3);
        driver.current(2, Y, 3);
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), driver.damageHere(base));
    }

    @Test
    void acceptedOverwritesInvalidateMetadataButTransparentAndRejectedWritesDoNot() {
        CompiledPalette palette = palette(PALETTE);
        ChunkDriver driver = driver(TestChunk.emptyChunk());
        CompiledPalette empty = new CompiledPalette();
        for (int path = 0; path < 4; path++) {
            place(driver, palette, 'X', 3, 4);
            driver.current(3, Y, 4);
            driver.block(Blocks.STRUCTURE_VOID.defaultBlockState(), Blocks.COBWEB.defaultBlockState());
            driver.block(null, null);
            driver.setBlockRange(3, Y, 4, Y + 1, Blocks.GOLD_BLOCK.defaultBlockState(), old -> false);
            assertEquals(Blocks.BRICKS.defaultBlockState(), driver.damageHere(empty));
            switch (path) {
                case 0 -> driver.block(Blocks.STONE.defaultBlockState());
                case 1 -> driver.setBlock(3, Y, 4, Blocks.GOLD_BLOCK.defaultBlockState());
                case 2 -> driver.setBlockRange(3, Y, 4, Y + 1, Blocks.GOLD_BLOCK.defaultBlockState());
                case 3 -> driver.setBlockRange(3, Y, 4, Y + 1, Blocks.GOLD_BLOCK.defaultBlockState(), old -> true);
            }
            assertNull(driver.damageHere(empty), "overwrite path " + path + " must erase the marker");
        }
    }

    @Test
    void verticalWriteLimitsAndGenerationCompletionBoundTheMetadataLifetime() throws Exception {
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk, Y, Y);
        CompiledPalette palette = palette(PALETTE);
        place(driver, palette, 'X', 3, 4);
        driver.current(3, Y + 1, 4).block(Blocks.STONE.defaultBlockState(), Blocks.COBWEB.defaultBlockState());
        assertNull(driver.damageHere(new CompiledPalette()), "a rejected write carries no marker");
        Field metadata = ChunkDriver.class.getDeclaredField("markerDamage");
        metadata.setAccessible(true);
        assertEquals(1, ((java.util.Map<?, ?>) metadata.get(driver)).size());
        driver.actuallyGenerate(chunk);
        assertNull(metadata.get(driver), "completed generation must release its per-position table");
    }

    @Test
    void markerDamageUsesAbsoluteCoordinatesInChunksAwayFromTheOrigin() {
        CompiledPalette palette = palette(PALETTE);
        ProtoChunk chunk = TestChunk.emptyChunk(new ChunkPos(7, -11));
        ChunkDriver driver = driver(chunk);
        driver.current(3, Y, 4);
        CompiledPalette.Placed placed = palette.placedAt('R', SEED, 115, Y, -172);
        BlockState target = placed.damagedAt(SEED, 115, Y, -172);
        Parts.writeMarker(driver, placed, placed.state(), SEED);
        driver.flushToChunk(chunk);
        driver.current(3, Y, 4);
        assertEquals(115, driver.getX());
        assertEquals(-172, driver.getZ());
        assertEquals(target, driver.damageHere(new CompiledPalette()));
        assertEquals(Blocks.STONE.defaultBlockState(), chunk.getBlockState(new BlockPos(115, Y, -172)));
    }

    @Test
    void lateMarkerWritesDoNotRecreateTheReleasedMetadata() throws Exception {
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = driver(chunk);
        driver.actuallyGenerate(chunk);
        place(driver, palette(PALETTE), 'X', 3, 4);
        Field metadata = ChunkDriver.class.getDeclaredField("markerDamage");
        metadata.setAccessible(true);
        assertNull(metadata.get(driver));
    }

    @Rule("TRAIT.007")
    @Rule("TRAIT.070")
    @Rule("TRAIT.071")
    @Test
    void mainAndDamageStatesFollowRotationAndMirrorWithIndependentOptOuts() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": {
                  "A": { "block": "minecraft:oak_stairs[facing=north]", "traits": {
                    "urbex:damaged": { "into": "minecraft:cobblestone_stairs[facing=north]" } } },
                  "B": { "block": "minecraft:oak_stairs[facing=north]", "traits": {
                    "urbex:rotatable": false,
                    "urbex:damaged": { "into": "minecraft:cobblestone_stairs[facing=north]" } } },
                  "C": { "block": "minecraft:oak_stairs[facing=north]", "traits": {
                    "urbex:damaged": { "into": { "block": "minecraft:cobblestone_stairs[facing=north]",
                      "traits": { "urbex:rotatable": false } } } } },
                  "D": { "block": "minecraft:oak_stairs[facing=north]", "traits": {
                    "urbex:rotatable": false,
                    "urbex:damaged": { "into": { "block": "minecraft:cobblestone_stairs[facing=north]",
                      "traits": { "urbex:rotatable": false } } } } }
                } }
                """);
        TagSnapshot tags = TagSnapshot.capture();
        // Choose a coordinate where the production damage decision keeps its replacement.
        int x = 0;
        while (Rng.floatAtPos(SEED, x, Y, 4, Rng.Purpose.DAMAGE) > .6f
                || Rng.floatAtPos(SEED, x, Y, 4, Rng.Purpose.DAMAGE_VARIANT) >= .7f) x++;
        assertTrue(x < 16, "the chosen address must belong to the test chunk");
        Map<Transform, Direction> transformed = Map.of(
                Transform.ROTATE_90, Direction.EAST,
                Transform.MIRROR_Z, Direction.SOUTH,
                Transform.MIRROR_90_X, Direction.WEST);
        for (var entry : transformed.entrySet()) {
            for (char marker : new char[]{'A', 'B', 'C', 'D'}) {
                ProtoChunk chunk = TestChunk.emptyChunk();
                ChunkDriver driver = driver(chunk);
                Transform transform = entry.getKey();
                CompiledPalette.Placed placed = palette.placedAt(marker, SEED, x, Y, 4);
                driver.current(x, Y, 4);
                Parts.writeMarker(driver, placed, Parts.transformMarker(placed, transform), SEED, transform);
                driver.flushToChunk(chunk);
                Direction mainFacing = marker == 'B' || marker == 'D' ? Direction.NORTH : entry.getValue();
                assertEquals(mainFacing, chunk.getBlockState(new BlockPos(x, Y, 4)).getValue(StairBlock.FACING),
                        marker + " main state under " + transform);
                driver.current(x, Y, 4);
                BlockState result = DamageArea.applyDamage(SEED, driver.getBlock(), tags, x, Y, 4,
                        .6f, driver.damageHere(palette), 63, Blocks.WATER.defaultBlockState());
                driver.block(result);
                driver.flushToChunk(chunk);
                Direction damageFacing = marker == 'C' || marker == 'D' ? Direction.NORTH : entry.getValue();
                BlockState committed = chunk.getBlockState(new BlockPos(x, Y, 4));
                assertTrue(committed.is(Blocks.COBBLESTONE_STAIRS), "the damage pass must actually replace the block");
                assertEquals(damageFacing, committed.getValue(StairBlock.FACING),
                        marker + " damage satellite under " + transform);
            }
        }
    }

    @Rule("LOAD.021")
    @Rule("TRAIT.071")
    @Test
    void rotationOptOutIsKeptPerSlotForBothTheMarkerAndItsDamageSatellite() {
        CompiledPalette palette = palette("""
                { "version": 2, "palette": {
                  "W": { "kind": "weighted", "choices": [
                    { "share": 0.5, "block": "minecraft:oak_stairs[facing=north]" },
                    { "rest": true, "block": "minecraft:oak_stairs[facing=north]",
                      "traits": { "urbex:rotatable": false } } ] },
                  "R": { "block": "minecraft:stone", "traits": {
                    "urbex:damaged": { "into": { "kind": "weighted", "choices": [
                      { "share": 0.5, "block": "minecraft:cobblestone_stairs[facing=north]" },
                      { "rest": true, "block": "minecraft:cobblestone_stairs[facing=north]",
                        "traits": { "urbex:rotatable": false } } ] } } } }
                } }
                """);
        Set<Direction> mainDirections = new HashSet<>();
        Set<Direction> damageDirections = new HashSet<>();
        ChunkDriver driver = driver(TestChunk.emptyChunk());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                CompiledPalette.Placed main = palette.placedAt('W', SEED, x, Y, z);
                driver.current(x, Y, z);
                Parts.writeMarker(driver, main, Parts.transformMarker(main, Transform.ROTATE_90),
                        SEED, Transform.ROTATE_90);
                driver.current(x, Y, z);
                mainDirections.add(driver.getBlock().getValue(StairBlock.FACING));
                CompiledPalette.Placed damaged = palette.placedAt('R', SEED, x, Y, z);
                Parts.writeMarker(driver, damaged, Parts.transformMarker(damaged, Transform.ROTATE_90),
                        SEED, Transform.ROTATE_90);
                driver.current(x, Y, z);
                damageDirections.add(driver.damageHere(palette).getValue(StairBlock.FACING));
            }
        }
        assertEquals(Set.of(Direction.NORTH, Direction.EAST), mainDirections);
        assertEquals(Set.of(Direction.NORTH, Direction.EAST), damageDirections);
    }

    private static void place(ChunkDriver driver, CompiledPalette palette, char marker, int x, int z) {
        CompiledPalette.Placed placed = palette.placedAt(marker, SEED, x, Y, z);
        driver.current(x, Y, z);
        Parts.writeMarker(driver, placed, placed.state(), SEED);
    }

    private static ChunkDriver driver(ProtoChunk chunk) {
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        return driver;
    }

    private static CompiledPalette palette(String json) {
        return new CompiledPalette(compiled(json));
    }

    private static Palette compiled(String json) {
        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition definition = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        CompiledV2Palette compiled = NodeResolver.resolve(definition, diagnostics)
                .flatMap(resolved -> CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft")),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                        "'urbex:marker_damage'", diagnostics))
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("compile failed")));
        return Palette.version2(Identifier.parse("urbex:marker_damage"), compiled);
    }
}
