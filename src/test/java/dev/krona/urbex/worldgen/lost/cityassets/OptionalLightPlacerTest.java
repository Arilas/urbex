package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSourceSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
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

    /** The marker's own block position; WEIGHT.043 addresses a placement list by it. */
    private static final BlockPos AT = new BlockPos(3, 64, 9);

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

    /**
     * {@code WEIGHT.042}, now true of a socket: an earlier opportunity cannot change a later one.
     *
     * <p>Both halves are asserted, and only the first held before {@code WEIGHT.043} was implemented.
     * An <em>unsupported</em> opportunity never reached {@code weightedOrder} and so consumed nothing
     * even from a sequential stream. A <em>supported</em> one whose candidates the world rejects did
     * reach it, drew a ticket, and shifted every later opportunity's draw — so which light stood in a
     * doorway depended on whether the floor beneath it had been rejected first. A placement list is
     * addressed by position now, so neither can.</p>
     */
    @Rule("WEIGHT.042")
    @Rule("WEIGHT.043")
    @Test
    void anEarlierOpportunityCannotChangeWhichCandidateALaterOneTakes() {
        List<LightSourceSettings.Entry> wall = List.of(
                entry("minecraft:wall_torch[facing=north]"),
                entry("minecraft:end_rod[facing=north]"));
        List<LightSourceSettings.Entry> floor = List.of(
                entry("minecraft:lantern[hanging=false]"),
                entry("minecraft:redstone_torch[lit=true]"));
        LightPool withFloor = pool(floor, wall, List.of(), List.of());
        LightPool wallOnly = pool(List.of(), wall, List.of(), List.of());

        OptionalLightPlacer.Attempt expected = OptionalLightPlacer.select(
                wallOnly, 17L, AT, (placement, supportDirection) -> true,
                attempt -> true).orElseThrow();

        OptionalLightPlacer.Attempt unsupported = OptionalLightPlacer.select(
                withFloor, 17L, AT,
                (placement, supportDirection) -> placement != LightPool.Placement.FLOOR,
                attempt -> true).orElseThrow();
        assertEquals(expected.state(), unsupported.state(),
                "an unsupported floor was never a draw, and still is not");
        assertEquals(LightPool.Placement.WALL, unsupported.placement());

        OptionalLightPlacer.Attempt rejected = OptionalLightPlacer.select(
                withFloor, 17L, AT, (placement, supportDirection) -> true,
                attempt -> attempt.placement() != LightPool.Placement.FLOOR).orElseThrow();
        assertEquals(expected.state(), rejected.state(),
                "and a supported floor whose candidates the world refuses no longer shifts the wall's "
                        + "choice - it did, while the pool drew a sequential ticket");
        assertEquals(LightPool.Placement.WALL, rejected.placement());
    }

    @Test
    void torchPoolCanPlaceOnFloor() {
        OptionalLightPlacer.Attempt selected = select(torchPool(),
                attempt -> attempt.placement() == LightPool.Placement.FLOOR).orElseThrow();

        assertEquals(Blocks.TORCH, selected.state().getBlock());
        assertEquals(Direction.DOWN, selected.supportDirection());
    }

    @Test
    void torchPoolCanPlaceAgainstEveryWallDirection() {
        for (Direction direction : List.of(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH)) {
            OptionalLightPlacer.Attempt selected = select(torchPool(),
                    attempt -> attempt.supportDirection() == direction).orElseThrow();

            assertEquals(Blocks.WALL_TORCH, selected.state().getBlock());
            assertEquals(direction, selected.supportDirection());
        }
    }

    @Test
    void aFloorAndWallOnlyPoolNeverAttemptsCeilingOrFreePlacement() {
        List<OptionalLightPlacer.Attempt> attempts = new ArrayList<>();

        Optional<OptionalLightPlacer.Attempt> selected = select(torchPool(), attempt -> {
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

    /**
     * Floor and wall torches only: the pool the removed {@code torch} boolean used to stand for,
     * and still the smallest one that exercises every anchored opportunity but the ceiling.
     */
    private static LightPool torchPool() {
        return pool(List.of(entry("minecraft:torch")),
                List.of(entry("minecraft:wall_torch")),
                List.of(), List.of());
    }

    private static Optional<OptionalLightPlacer.Attempt> select(LightPool pool,
                                                                 OptionalLightPlacer.Survival survival) {
        return OptionalLightPlacer.select(pool, 17L, AT, survival);
    }

    private static LightPool pool(List<LightSourceSettings.Entry> floor,
                                  List<LightSourceSettings.Entry> wall,
                                  List<LightSourceSettings.Entry> ceiling,
                                  List<LightSourceSettings.Entry> free) {
        return LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', new LightSourceSettings(floor, wall, ceiling, free, null, null));
    }

    private static LightSourceSettings.Entry entry(String block) {
        return new LightSourceSettings.Entry(1, block);
    }

    private static void assertJsonOrderRotation(List<Block> actual) {
        assertTrue(List.of(
                List.of(Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.REDSTONE_TORCH),
                List.of(Blocks.SOUL_LANTERN, Blocks.REDSTONE_TORCH, Blocks.LANTERN),
                List.of(Blocks.REDSTONE_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN)
        ).contains(actual), () -> "Unexpected fallback order " + actual);
    }
}
