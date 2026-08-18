package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSourceSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
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
        LightSourceSettings settings = decodeSettings("""
                {
                  "floor":[{"weight":1,"block":"minecraft:torch"}],
                  "wall":[{"weight":2,"block":"minecraft:wall_torch"}],
                  "ceiling":[{"weight":3,"block":"minecraft:lantern"}],
                  "free":[{"weight":4,"block":"minecraft:redstone_torch"}]
                }
                """);

        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);
        assertEquals(1, pool.weightedOrder(LightPool.Placement.FLOOR, 1L, 0, 64, 0).size());
        assertEquals(1, pool.weightedOrder(LightPool.Placement.WALL, 1L, 0, 64, 0).size());
        assertEquals(1, pool.weightedOrder(LightPool.Placement.CEILING, 1L, 0, 64, 0).size());
        assertEquals(1, pool.weightedOrder(LightPool.Placement.FREE, 1L, 0, 64, 0).size());
        assertEquals(4, pool.allCandidates().size());
    }

    @Test
    void paletteCompilationRejectsALightSourceWithNothingToPlace() {
        PaletteDefinition palette = decodePalette("""
                {"palette":[{"char":"L","lightSource":{
                  "floor":[],"wall":[],"ceiling":[],"free":[]
                }}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(palette)));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
        assertTrue(error.getMessage().contains("entry 'L'"));
        assertTrue(error.getMessage().contains("floor, wall, ceiling, or free"));
    }

    @Test
    void paletteCompilationRejectsABareLightSourceOnAnEntryWithNoBlock() {
        PaletteDefinition palette = decodePalette("""
                {"palette":[{"char":"L","lightSource":true}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(palette)));
        assertTrue(error.getMessage().contains("entry 'L'"));
        assertTrue(error.getMessage().contains("names nothing to place"));
    }

    @Test
    void paletteCompilationRejectsALightSourceOnBlocksThatEmitNothing() {
        PaletteDefinition palette = decodePalette("""
                {"palette":[{"char":"L","block":"minecraft:stone","lightSource":true}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(palette)));
        assertTrue(error.getMessage().contains("entry 'L'"));
        assertTrue(error.getMessage().contains("emit any light"));
    }

    @Test
    void anInPlaceSourceKeepsItsOwnBlockAndCompilesItsReplacement() {
        PaletteDefinition palette = decodePalette("""
                {"palette":[{
                  "char":"L",
                  "block":"minecraft:lantern[hanging=true]",
                  "lightSource":{"unlit":"minecraft:iron_chain[axis=y]"}
                }]}
                """);

        Palette.PE entry = new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(palette))
                .getPalette().get('L');
        BlockState lit = assertInstanceOf(BlockState.class, entry.blocks());
        assertEquals(Blocks.LANTERN, lit.getBlock());
        assertNull(entry.info().lightSource().pool());
        assertEquals(Blocks.IRON_CHAIN,
                ((BlockChoice.One) entry.info().lightSource().unlit()).state().getBlock());
    }

    @Test
    void aWeightedReplacementDrawsFromThePositionAndNothingElse() {
        PaletteDefinition palette = decodePalette("""
                {"palette":[{
                  "char":"L",
                  "block":"minecraft:lantern[hanging=true]",
                  "lightSource":{"unlitBlocks":[
                    {"random":64,"block":"minecraft:iron_chain[axis=y]"},
                    {"random":64,"block":"minecraft:air"}
                  ]}
                }]}
                """);

        LightSource source = new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(palette))
                .getPalette().get('L').info().lightSource();
        assertInstanceOf(BlockChoice.Weighted.class, source.unlit());
        BlockPos pos = new BlockPos(11, 71, -4);
        assertEquals(source.unlitAt(9001L, pos), source.unlitAt(9001L, pos));
    }

    @Test
    void declaringBothReplacementSpellingsIsADecodeError() {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                        {"palette":[{
                          "char":"L",
                          "block":"minecraft:lantern",
                          "lightSource":{
                            "unlit":"minecraft:air",
                            "unlitBlocks":[{"random":1,"block":"minecraft:air"}]
                          }
                        }]}
                        """));
        assertTrue(result.error().isPresent());
        assertTrue(result.error().orElseThrow().message().contains("one replacement"));
    }

    @Test
    void lightSourceFalseIsADecodeErrorRatherThanASilentNothing() {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                        {"palette":[{"char":"L","block":"minecraft:lantern","lightSource":false}]}
                        """));
        assertTrue(result.error().isPresent());
        assertTrue(result.error().orElseThrow().message().contains("omit the field"));
    }

    @Test
    void paletteCompilationRejectsNonpositiveWeightWithFullCandidateContext() {
        for (int weight : List.of(0, -3)) {
            PaletteDefinition palette = decodePalette("""
                    {"palette":[{"char":"L","lightSource":{
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
        LightSourceSettings settings = decodeSettings("""
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
        LightSourceSettings settings = decodeSettings("""
                {"floor":[{"weight":1,"block":"minecraft:redstone_torch[lit=true]"}]}
                """);

        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);
        LightPool.Candidate candidate = pool.allCandidates().iterator().next();
        assertTrue(candidate.state().getLightEmission() > 0);
    }

    @Test
    void compileRejectsHorizontalOnlyStateInVerticalPlacement() {
        for (LightPool.Placement placement : List.of(LightPool.Placement.FLOOR, LightPool.Placement.CEILING)) {
            LightSourceSettings settings = placement == LightPool.Placement.FLOOR
                    ? new LightSourceSettings(
                    List.of(new LightSourceSettings.Entry(1, "minecraft:wall_torch[facing=north]")),
                    List.of(), List.of(), List.of(), null, null)
                    : new LightSourceSettings(
                    List.of(), List.of(),
                    List.of(new LightSourceSettings.Entry(1, "minecraft:wall_torch[facing=north]")),
                    List.of(), null, null);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
            assertTrue(error.getMessage().contains("placement '" + placement.name().toLowerCase() + "'"));
            assertTrue(error.getMessage().contains("cannot orient a horizontal-only state"));
        }
    }

    @Test
    void compileRejectsHangingOnlyStateInWallPlacement() {
        LightSourceSettings settings = new LightSourceSettings(
                List.of(),
                List.of(new LightSourceSettings.Entry(1, "minecraft:lantern[hanging=false]")),
                List.of(), List.of(), null, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
        assertTrue(error.getMessage().contains("placement 'wall'"));
        assertTrue(error.getMessage().contains("cannot orient a hanging-only state to a wall"));
    }

    @Test
    void malformedStateReportsFullCandidateContext() {
        String malformed = "minecraft:torch[not_a_property=true]";
        LightSourceSettings settings = decodeSettings("""
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
        LightSourceSettings settings = decodeSettings("""
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
        LightSourceSettings settings = decodeSettings("""
                {"floor":[
                  {"weight":1,"block":"minecraft:lantern[hanging=false]"},
                  {"weight":1,"block":"minecraft:soul_lantern[hanging=false]"},
                  {"weight":1,"block":"minecraft:redstone_torch[lit=true]"}
                ]}
                """);
        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);
        List<LightPool.Candidate> order = pool.weightedOrder(LightPool.Placement.FLOOR, 5L, 0, 64, 0);

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

    /**
     * {@code VER.004}: version 1 does not become stricter, and this is the case that proved it can.
     *
     * <p>{@code WEIGHT.043} first materialised a placement list with
     * {@code CompiledPalette.distributeSlots}, which reads a version 1 weight as an absolute slot count
     * and clips a list totalling more than 128. A socket weight was never a slot count — it was a ticket
     * share — so {@code [1000, 1]} became {@code [128, 0]} and a candidate that had been placed one time
     * in a thousand could never be placed at all. A guard added to notice that refused the world
     * instead, which is the same rule broken the other way: a pack that loads today must keep
     * loading.</p>
     *
     * <p>{@code Apportion.slots} is what both readings were reaching for. {@code WEIGHT.062} gives the
     * second candidate one slot of 128 — rarer than one in a thousand, because a socket has 128 slots,
     * and present, which is the property a pack author can see.</p>
     */
    @Rule("VER.004")
    @Rule("WEIGHT.062")
    @Test
    void aVersion1SocketWeightedFarBelowItsSiblingStillLoadsAndStillAppears() {
        LightSourceSettings settings = new LightSourceSettings(
                List.of(
                        new LightSourceSettings.Entry(1000, "minecraft:torch"),
                        new LightSourceSettings.Entry(1, "minecraft:soul_torch")),
                List.of(), List.of(), List.of(), null, null);

        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);

        assertEquals(2, pool.allCandidates().size(), "neither candidate was refused or dropped");
        Set<Block> winners = new LinkedHashSet<>();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                winners.add(pool.weightedOrder(LightPool.Placement.FLOOR, 1L, x, 64, z)
                        .getFirst().state().getBlock());
            }
        }
        assertEquals(Set.of(Blocks.TORCH, Blocks.SOUL_TORCH), winners,
                "and the rare one still wins somewhere: WEIGHT.062 owes it a slot, where clipping the "
                        + "list to 128 absolute counts owed it nothing");
    }

    /**
     * The same rule at the size the specification cannot satisfy it at, which version 1 may still load.
     *
     * <p>{@code Apportion.slots} has a precondition of at most one share per slot, because past that
     * {@code WEIGHT.062} is unsatisfiable; version 2 refuses such a node at load with {@code DIAG.044}.
     * Version 1 has no such rule, so a 200-candidate placement list has to keep loading rather than
     * throw a precondition out of a world load with no asset in it.</p>
     */
    @Rule("VER.004")
    @Rule("WEIGHT.063")
    @Test
    void aVersion1SocketWithMoreCandidatesThanSlotsStillLoads() {
        List<LightSourceSettings.Entry> floor = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            floor.add(new LightSourceSettings.Entry(1, "minecraft:torch"));
        }
        LightSourceSettings settings =
                new LightSourceSettings(floor, List.of(), List.of(), List.of(), null, null);

        LightPool pool = LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings);

        assertEquals(200, pool.allCandidates().size());
        assertEquals(Blocks.TORCH, pool.weightedOrder(LightPool.Placement.FLOOR, 1L, 0, 64, 0)
                .getFirst().state().getBlock(), "and it still places something");
    }

    @Test
    void compileRejectsProgrammaticNonpositiveWeight() {
        LightSourceSettings settings = new LightSourceSettings(
                List.of(new LightSourceSettings.Entry(0, "minecraft:lantern")),
                List.of(), List.of(), List.of(), null, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', settings));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
        assertTrue(error.getMessage().contains("L"));
        assertTrue(error.getMessage().contains("floor"));
        assertTrue(error.getMessage().contains("minecraft:lantern"));
    }

    @Test
    void removedTorchSpellingFailsTheLoadNamingWhatToWriteInstead() {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"palette":[{
                  "char":"L",
                  "block":"minecraft:wall_torch[facing=north]",
                  "torch":true
                }]}
                """));
        assertTrue(result.result().isPresent());
        PaletteDefinition paletteDefinition = result.result().orElseThrow();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(paletteDefinition)));
        assertTrue(error.getMessage().contains("'torch'"));
        assertTrue(error.getMessage().contains("lightSource"));
        assertTrue(error.getMessage().contains("urbex:test_lights"));
    }

    @Test
    void removedLightSpellingFailsTheLoadNamingTheRename() {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"palette":[{
                  "char":"L",
                  "light":{"floor":[{"weight":1,"block":"minecraft:torch"}]}
                }]}
                """));
        assertTrue(result.result().isPresent());
        PaletteDefinition paletteDefinition = result.result().orElseThrow();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Palette(PALETTE_ID, BuiltInRegistries.BLOCK, null, List.of(paletteDefinition)));
        assertTrue(error.getMessage().contains("'light'"));
        assertTrue(error.getMessage().contains("lightSource"));
    }

    @Test
    void typedLightOnlyEntryUsesRepresentativeState() {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"palette":[{
                  "char":"L",
                  "lightSource":{"wall":[
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
        assertEquals(2, entry.info().lightSource().pool().allCandidates().size());
    }

    private static LightSourceSettings decodeSettings(String json) {
        DataResult<LightSourceSettings> result = parseSettings(json);
        assertTrue(result.result().isPresent(), () -> result.error().map(Object::toString).orElse("unknown decode error"));
        return result.result().orElseThrow();
    }

    private static DataResult<LightSourceSettings> parseSettings(String json) {
        return LightSourceSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static PaletteDefinition decodePalette(String json) {
        DataResult<PaletteDefinition> result = PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        assertTrue(result.result().isPresent(), () -> result.error().map(Object::toString).orElse("unknown decode error"));
        return result.result().orElseThrow();
    }
}
