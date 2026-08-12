package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightPoolTest {

    private static final Identifier PALETTE_ID = Identifier.fromNamespaceAndPath("urbex", "test_lights");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void codecPreservesAllFourPlacementGroups() {
        LightSettings settings = decodeSettings("""
                {
                  "floor":[{"weight":1,"block":"minecraft:torch"}],
                  "wall":[{"weight":2,"block":"minecraft:wall_torch"}],
                  "ceiling":[{"weight":3,"block":"minecraft:lantern"}],
                  "free":[{"weight":4,"block":"minecraft:redstone_torch"}]
                }
                """);

        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);
        assertEquals(1, pool.weightedOrder(LightPool.Placement.FLOOR, RandomSource.create(1L)).size());
        assertEquals(1, pool.weightedOrder(LightPool.Placement.WALL, RandomSource.create(1L)).size());
        assertEquals(1, pool.weightedOrder(LightPool.Placement.CEILING, RandomSource.create(1L)).size());
        assertEquals(1, pool.weightedOrder(LightPool.Placement.FREE, RandomSource.create(1L)).size());
        assertEquals(4, pool.allCandidates().size());
    }

    @Test
    void paletteCompilationRejectsPoolWithoutCandidatesWithResourceAndMarkerContext() {
        PaletteDefinition palette = decodePalette("""
                {"palette":[{"char":"L","light":{
                  "floor":[],"wall":[],"ceiling":[],"free":[]
                }}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(palette)));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
        assertTrue(error.getMessage().contains("marker 'L'"));
        assertTrue(error.getMessage().contains("floor, wall, ceiling, or free"));
    }

    @Test
    void paletteCompilationRejectsNonpositiveWeightWithFullCandidateContext() {
        for (int weight : List.of(0, -3)) {
            PaletteDefinition palette = decodePalette("""
                    {"palette":[{"char":"L","light":{
                      "floor":[{"weight":%d,"block":"minecraft:torch"}]
                    }}]}
                    """.formatted(weight));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(palette)));
            assertTrue(error.getMessage().contains("urbex:test_lights"));
            assertTrue(error.getMessage().contains("marker 'L'"));
            assertTrue(error.getMessage().contains("placement 'floor'"));
            assertTrue(error.getMessage().contains("candidate #1 'minecraft:torch'"));
            assertTrue(error.getMessage().contains("weight must be positive"));
        }
    }

    @Test
    void compileRejectsBlockThatEmitsNoLight() {
        LightSettings settings = decodeSettings("""
                {"floor":[{"weight":1,"block":"minecraft:stone"}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
        assertTrue(error.getMessage().contains("L"));
        assertTrue(error.getMessage().contains("floor"));
        assertTrue(error.getMessage().contains("minecraft:stone"));
    }

    @Test
    void compileAcceptsWeakNonzeroCustomLight() {
        LightSettings settings = decodeSettings("""
                {"floor":[{"weight":1,"block":"minecraft:redstone_torch[lit=true]"}]}
                """);

        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);
        LightPool.Candidate candidate = pool.allCandidates().iterator().next();
        assertTrue(candidate.state().getLightEmission() > 0);
    }

    @Test
    void compileRejectsHorizontalOnlyStateInVerticalPlacement() {
        for (LightPool.Placement placement : List.of(LightPool.Placement.FLOOR, LightPool.Placement.CEILING)) {
            LightSettings settings = placement == LightPool.Placement.FLOOR
                    ? new LightSettings(
                    List.of(new LightSettings.Entry(1, "minecraft:wall_torch[facing=north]")),
                    List.of(), List.of(), List.of())
                    : new LightSettings(
                    List.of(), List.of(),
                    List.of(new LightSettings.Entry(1, "minecraft:wall_torch[facing=north]")),
                    List.of());

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
            assertTrue(error.getMessage().contains("placement '" + placement.name().toLowerCase() + "'"));
            assertTrue(error.getMessage().contains("cannot orient a horizontal-only state"));
        }
    }

    @Test
    void compileRejectsHangingOnlyStateInWallPlacement() {
        LightSettings settings = new LightSettings(
                List.of(),
                List.of(new LightSettings.Entry(1, "minecraft:lantern[hanging=false]")),
                List.of(), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
        assertTrue(error.getMessage().contains("placement 'wall'"));
        assertTrue(error.getMessage().contains("cannot orient a hanging-only state to a wall"));
    }

    @Test
    void malformedStateReportsFullCandidateContext() {
        String malformed = "minecraft:torch[not_a_property=true]";
        LightSettings settings = decodeSettings("""
                {"ceiling":[{"weight":1,"block":"minecraft:torch[not_a_property=true]"}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
        assertTrue(error.getMessage().contains("L"));
        assertTrue(error.getMessage().contains("ceiling"));
        assertTrue(error.getMessage().contains(malformed));
    }

    @Test
    void representativeUsesFirstCandidateOfFirstNonemptyGroup() {
        LightSettings settings = decodeSettings("""
                {
                  "wall":[
                    {"weight":1,"block":"minecraft:soul_wall_torch"},
                    {"weight":1,"block":"minecraft:wall_torch"}
                  ],
                  "ceiling":[{"weight":1,"block":"minecraft:lantern"}],
                  "free":[{"weight":1,"block":"minecraft:torch"}]
                }
                """);

        BlockState expected = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:soul_wall_torch"))
                .defaultBlockState();
        assertEquals(expected, LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings).representative());
    }

    @Test
    void weightedOrderPutsWinnerFirstThenWrapsJsonOrder() {
        LightSettings settings = decodeSettings("""
                {"floor":[
                  {"weight":1,"block":"minecraft:lantern[hanging=false]"},
                  {"weight":1,"block":"minecraft:soul_lantern[hanging=false]"},
                  {"weight":1,"block":"minecraft:redstone_torch[lit=true]"}
                ]}
                """);
        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);
        List<LightPool.Candidate> order = pool.weightedOrder(LightPool.Placement.FLOOR, RandomSource.create(5L));

        List<Block> states = order.stream()
                .map(candidate -> candidate.state().getBlock())
                .toList();
        List<Block> jsonOrder = List.of(Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.REDSTONE_TORCH);
        int winner = jsonOrder
                .indexOf(states.getFirst());
        assertTrue(winner >= 0, () -> "Unexpected winner " + states.getFirst());
        assertEquals(List.of(
                states.getFirst(),
                jsonOrder.get((winner + 1) % 3),
                jsonOrder.get((winner + 2) % 3)
        ), states);
    }

    @Test
    void compileRejectsWeightSumOverflowWithCandidateContext() {
        LightSettings settings = new LightSettings(
                List.of(
                        new LightSettings.Entry(Integer.MAX_VALUE, "minecraft:torch"),
                        new LightSettings.Entry(1, "minecraft:soul_torch")),
                List.of(), List.of(), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
        assertTrue(error.getMessage().contains("L"));
        assertTrue(error.getMessage().contains("floor"));
        assertTrue(error.getMessage().contains("minecraft:soul_torch"));
    }

    @Test
    void compileRejectsProgrammaticNonpositiveWeight() {
        LightSettings settings = new LightSettings(
                List.of(new LightSettings.Entry(0, "minecraft:lantern")),
                List.of(), List.of(), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
        assertTrue(error.getMessage().contains("L"));
        assertTrue(error.getMessage().contains("floor"));
        assertTrue(error.getMessage().contains("minecraft:lantern"));
    }

    @Test
    void completeLegacyTorchEntryStillCompilesWithoutTypedPool() {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"palette":[{
                  "char":"L",
                  "block":"minecraft:wall_torch[facing=north]",
                  "torch":true
                }]}
                """));
        assertTrue(result.result().isPresent());
        PaletteDefinition paletteDefinition = result.result().orElseThrow();

        Palette.PE entry = new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(paletteDefinition)).getPalette().get('L');
        assertInstanceOf(BlockState.class, entry.blocks());
        assertTrue(entry.info().isTorch());
        assertNull(entry.info().light());
    }

    @Test
    void typedLightOnlyEntryUsesRepresentativeState() {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"palette":[{
                  "char":"L",
                  "light":{"wall":[
                    {"weight":2,"block":"minecraft:soul_wall_torch[facing=north]"},
                    {"weight":1,"block":"minecraft:wall_torch[facing=south]"}
                  ]}
                }]}
                """));
        assertTrue(result.result().isPresent());
        PaletteDefinition paletteDefinition = result.result().orElseThrow();

        Palette.PE entry = new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(paletteDefinition)).getPalette().get('L');
        BlockState representative = assertInstanceOf(BlockState.class, entry.blocks());
        assertEquals(Blocks.SOUL_WALL_TORCH, representative.getBlock());
        assertTrue(entry.info().isSpecial());
        assertEquals(2, entry.info().light().allCandidates().size());
    }

    @Test
    void legacyTorchPoolContainsOnlyImmutableFloorAndWallCandidates() {
        LightPool pool = LightPool.legacyTorch();

        assertEquals(Blocks.TORCH, pool.weightedOrder(LightPool.Placement.FLOOR, RandomSource.create(1L))
                .getFirst().state().getBlock());
        assertEquals(Blocks.WALL_TORCH, pool.weightedOrder(LightPool.Placement.WALL, RandomSource.create(1L))
                .getFirst().state().getBlock());
        assertTrue(pool.weightedOrder(LightPool.Placement.CEILING, RandomSource.create(1L)).isEmpty());
        assertTrue(pool.weightedOrder(LightPool.Placement.FREE, RandomSource.create(1L)).isEmpty());
        assertEquals(2, pool.allCandidates().size());
        assertThrows(UnsupportedOperationException.class, pool.allCandidates()::clear);
    }

    private static LightSettings decodeSettings(String json) {
        DataResult<LightSettings> result = parseSettings(json);
        assertTrue(result.result().isPresent(), () -> result.error().map(Object::toString).orElse("unknown decode error"));
        return result.result().orElseThrow();
    }

    private static DataResult<LightSettings> parseSettings(String json) {
        return LightSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static PaletteDefinition decodePalette(String json) {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        assertTrue(result.result().isPresent(), () -> result.error().map(Object::toString).orElse("unknown decode error"));
        return result.result().orElseThrow();
    }
}
