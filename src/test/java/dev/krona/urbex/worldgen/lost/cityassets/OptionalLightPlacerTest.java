package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.data.LightSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalLightPlacerTest {

    private static final Identifier PALETTE_ID = Identifier.fromNamespaceAndPath("urbex", "placer_test");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void floorIsSelectedBeforeWallWhenBothCanSurvive() {
        LightPool pool = pool(
                List.of(entry("minecraft:lantern[hanging=false]")),
                List.of(entry("minecraft:wall_torch[facing=north]")),
                List.of(),
                List.of());

        OptionalLightPlacer.Attempt attempt = select(pool, candidate -> true).orElseThrow();

        assertEquals(LightPool.Placement.FLOOR, attempt.placement());
        assertEquals(Direction.DOWN, attempt.supportDirection());
        assertEquals(Blocks.LANTERN, attempt.state().getBlock());
    }

    @Test
    void wallSupportsAreAttemptedWestEastNorthSouth() {
        LightPool pool = pool(
                List.of(),
                List.of(entry("minecraft:wall_torch")),
                List.of(),
                List.of());
        List<Direction> supports = new ArrayList<>();

        OptionalLightPlacer.Attempt selected = select(pool, attempt -> {
            supports.add(attempt.supportDirection());
            return attempt.supportDirection() == Direction.SOUTH;
        }).orElseThrow();

        assertEquals(List.of(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH), supports);
        assertEquals(Direction.SOUTH, selected.supportDirection());
    }

    @Test
    void westSupportedWallTorchFacesEastBeforeSurvivalCheck() {
        LightPool pool = pool(
                List.of(),
                List.of(entry("minecraft:wall_torch")),
                List.of(),
                List.of());

        OptionalLightPlacer.Attempt attempt = select(pool,
                candidate -> candidate.supportDirection() == Direction.WEST).orElseThrow();

        assertEquals(Direction.EAST, attempt.state().getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    @Test
    void eastSupportedEndRodFacesWestBeforeSurvivalCheck() {
        LightPool pool = pool(
                List.of(),
                List.of(entry("minecraft:end_rod")),
                List.of(),
                List.of());

        OptionalLightPlacer.Attempt attempt = select(pool,
                candidate -> candidate.supportDirection() == Direction.EAST).orElseThrow();

        assertEquals(Direction.WEST, attempt.state().getValue(BlockStateProperties.FACING));
    }

    @Test
    void ceilingLanternIsHangingBeforeSurvivalCheck() {
        LightPool pool = pool(
                List.of(),
                List.of(),
                List.of(entry("minecraft:lantern[hanging=false]")),
                List.of());

        OptionalLightPlacer.Attempt attempt = select(pool, candidate -> true).orElseThrow();

        assertTrue(attempt.state().getValue(BlockStateProperties.HANGING));
        assertEquals(Direction.UP, attempt.supportDirection());
    }

    @Test
    void floorLanternIsStandingBeforeSurvivalCheck() {
        LightPool pool = pool(
                List.of(entry("minecraft:lantern[hanging=true]")),
                List.of(),
                List.of(),
                List.of());

        OptionalLightPlacer.Attempt attempt = select(pool, candidate -> true).orElseThrow();

        assertFalse(attempt.state().getValue(BlockStateProperties.HANGING));
        assertEquals(Direction.DOWN, attempt.supportDirection());
    }

    @Test
    void rejectedWeightedWinnerFallsThroughJsonOrderBeforeWall() {
        LightPool pool = pool(
                List.of(
                        entry("minecraft:lantern[hanging=false]"),
                        entry("minecraft:soul_lantern[hanging=false]"),
                        entry("minecraft:redstone_torch[lit=true]")),
                List.of(entry("minecraft:wall_torch")),
                List.of(),
                List.of());
        List<OptionalLightPlacer.Attempt> attempts = new ArrayList<>();

        OptionalLightPlacer.Attempt selected = select(pool, attempt -> {
            attempts.add(attempt);
            return attempts.size() == 3;
        }).orElseThrow();

        assertEquals(List.of(
                LightPool.Placement.FLOOR,
                LightPool.Placement.FLOOR,
                LightPool.Placement.FLOOR),
                attempts.stream().map(OptionalLightPlacer.Attempt::placement).toList());
        assertJsonOrderRotation(attempts.stream().map(attempt -> attempt.state().getBlock()).toList());
        assertEquals(LightPool.Placement.FLOOR, selected.placement());
    }

    @Test
    void freeCandidateIsAttemptedAfterAllSupportedOpportunities() {
        LightPool pool = pool(
                List.of(entry("minecraft:lantern[hanging=false]")),
                List.of(entry("minecraft:wall_torch[facing=north]")),
                List.of(entry("minecraft:lantern")),
                List.of(entry("minecraft:glowstone")));
        List<OptionalLightPlacer.Attempt> attempts = new ArrayList<>();

        OptionalLightPlacer.Attempt selected = select(pool, attempt -> {
            attempts.add(attempt);
            return attempt.placement() == LightPool.Placement.FREE;
        }).orElseThrow();

        assertEquals(Arrays.asList(
                Direction.DOWN,
                Direction.WEST,
                Direction.EAST,
                Direction.NORTH,
                Direction.SOUTH,
                Direction.UP,
                null), attempts.stream().map(OptionalLightPlacer.Attempt::supportDirection).toList());
        assertEquals(LightPool.Placement.FREE, selected.placement());
        assertEquals(Blocks.GLOWSTONE, selected.state().getBlock());
    }

    @Test
    void allRejectedAttemptsReturnEmpty() {
        LightPool pool = pool(
                List.of(entry("minecraft:torch")),
                List.of(entry("minecraft:wall_torch")),
                List.of(entry("minecraft:lantern")),
                List.of(entry("minecraft:glowstone")));

        Optional<OptionalLightPlacer.Attempt> selected = select(pool, attempt -> false);

        assertTrue(selected.isEmpty());
    }

    @Test
    void legacyTorchCanPlaceOnFloor() {
        OptionalLightPlacer.Attempt selected = select(LightPool.legacyTorch(),
                attempt -> attempt.placement() == LightPool.Placement.FLOOR).orElseThrow();

        assertEquals(Blocks.TORCH, selected.state().getBlock());
        assertEquals(Direction.DOWN, selected.supportDirection());
    }

    @Test
    void legacyTorchCanPlaceAgainstEveryWallDirection() {
        for (Direction direction : List.of(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH)) {
            OptionalLightPlacer.Attempt selected = select(LightPool.legacyTorch(),
                    attempt -> attempt.supportDirection() == direction).orElseThrow();

            assertEquals(Blocks.WALL_TORCH, selected.state().getBlock());
            assertEquals(direction, selected.supportDirection());
        }
    }

    @Test
    void legacyTorchNeverAttemptsCeilingOrFreePlacement() {
        List<OptionalLightPlacer.Attempt> attempts = new ArrayList<>();

        Optional<OptionalLightPlacer.Attempt> selected = select(LightPool.legacyTorch(), attempt -> {
            attempts.add(attempt);
            return false;
        });

        assertTrue(selected.isEmpty());
        assertEquals(List.of(
                Direction.DOWN,
                Direction.WEST,
                Direction.EAST,
                Direction.NORTH,
                Direction.SOUTH), attempts.stream().map(OptionalLightPlacer.Attempt::supportDirection).toList());
        assertTrue(attempts.stream().noneMatch(attempt ->
                attempt.placement() == LightPool.Placement.CEILING
                        || attempt.placement() == LightPool.Placement.FREE));
    }

    private static Optional<OptionalLightPlacer.Attempt> select(LightPool pool,
                                                                 OptionalLightPlacer.Survival survival) {
        return OptionalLightPlacer.select(pool, RandomSource.create(17L), survival);
    }

    private static LightPool pool(List<LightSettings.Entry> floor,
                                  List<LightSettings.Entry> wall,
                                  List<LightSettings.Entry> ceiling,
                                  List<LightSettings.Entry> free) {
        return LightPool.compile(PALETTE_ID, 'L', new LightSettings(floor, wall, ceiling, free));
    }

    private static LightSettings.Entry entry(String block) {
        return new LightSettings.Entry(1, block);
    }

    private static void assertJsonOrderRotation(List<Block> actual) {
        assertTrue(List.of(
                List.of(Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.REDSTONE_TORCH),
                List.of(Blocks.SOUL_LANTERN, Blocks.REDSTONE_TORCH, Blocks.LANTERN),
                List.of(Blocks.REDSTONE_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN)
        ).contains(actual), () -> "Unexpected fallback order " + actual);
    }
}
