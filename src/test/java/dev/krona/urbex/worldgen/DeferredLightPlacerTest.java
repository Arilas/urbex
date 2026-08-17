package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.DensitySelector;
import dev.krona.urbex.worldgen.lost.cityassets.BlockChoice;
import dev.krona.urbex.worldgen.lost.cityassets.LightPool;
import dev.krona.urbex.worldgen.lost.cityassets.LightSource;
import dev.krona.urbex.worldgen.lost.regassets.data.LightSourceSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredLightPlacerTest {

    private static final Identifier PALETTE_ID = Identifier.fromNamespaceAndPath("urbex", "deferred_test");
    private static final int OWNER_X = 3;
    private static final int OWNER_Z = -2;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void alternateContextQueuePlansEveryQueuedMarker() {
        LightSource free = source(pool(List.of(), List.of(), List.of(),
                List.of(entry(1, "minecraft:glowstone"))));
        BlockPos first = pos(1, 70, 1);
        BlockPos second = pos(2, 70, 1);
        LightTodoQueue queue = new LightTodoQueue(OWNER_X, OWNER_Z);
        queue.add(first, free);
        queue.add(second, free);

        List<DeferredLightPlacer.Planned> planned = plan(
                queue.closeAndDrain(), (candidate, fallback) -> fallback);

        assertEquals(List.of(
                new DeferredLightPlacer.Planned(first, Blocks.GLOWSTONE.defaultBlockState()),
                new DeferredLightPlacer.Planned(second, Blocks.GLOWSTONE.defaultBlockState())), planned);
    }

    @Test
    void aSocketWithNowhereToHangItPlansItsReplacement() {
        LightSource lantern = new LightSource(wallPool(),
                BlockChoice.of(Blocks.IRON_CHAIN.defaultBlockState()));
        BlockPos marker = pos(5, 70, 5);

        List<DeferredLightPlacer.Planned> planned = DeferredLightPlacer.plan(
                OWNER_X, OWNER_Z, 19L, List.of(new LightTodoQueue.Todo(marker, lantern)),
                candidate -> Blocks.AIR.defaultBlockState(),
                (unusedMarker, supportDirection, stateAt) -> false,
                (unusedMarker, attempt, stateAt) -> true);

        assertEquals(List.of(new DeferredLightPlacer.Planned(marker, Blocks.IRON_CHAIN.defaultBlockState())),
                planned);
    }

    @Test
    void aSocketWithNowhereToHangItAndNoReplacementPlansNothing() {
        LightSource bare = source(wallPool());
        BlockPos marker = pos(5, 70, 5);

        List<DeferredLightPlacer.Planned> planned = DeferredLightPlacer.plan(
                OWNER_X, OWNER_Z, 19L, List.of(new LightTodoQueue.Todo(marker, bare)),
                candidate -> Blocks.AIR.defaultBlockState(),
                (unusedMarker, supportDirection, stateAt) -> false,
                (unusedMarker, attempt, stateAt) -> true);

        assertTrue(planned.isEmpty());
    }

    @Test
    void borderMarkersUseOnlyInwardOwnerChunkSupport() {
        LightPool wall = wallPool();
        BlockPos westBorder = pos(0, 70, 1);
        BlockPos eastBorder = pos(15, 70, 1);
        Map<BlockPos, BlockState> surroundings = Map.of(
                westBorder.west(), Blocks.STONE.defaultBlockState(),
                westBorder.east(), Blocks.STONE.defaultBlockState(),
                eastBorder.west(), Blocks.STONE.defaultBlockState(),
                eastBorder.east(), Blocks.STONE.defaultBlockState());
        List<LightTodoQueue.Todo> todos = List.of(
                new LightTodoQueue.Todo(westBorder, source(wall)),
                new LightTodoQueue.Todo(eastBorder, source(wall)));

        List<DeferredLightPlacer.Planned> planned = DeferredLightPlacer.plan(
                OWNER_X, OWNER_Z, 19L, todos,
                candidate -> surroundings.getOrDefault(candidate, Blocks.AIR.defaultBlockState()),
                (marker, supportDirection, stateAt) -> {
                    BlockPos support = marker.relative(supportDirection);
                    assertEquals(OWNER_X, support.getX() >> 4);
                    assertEquals(OWNER_Z, support.getZ() >> 4);
                    return !stateAt.apply(support).isAir();
                },
                (marker, attempt, stateAt) -> true);

        assertEquals(2, planned.size());
        assertEquals(Direction.WEST, planned.get(0).state()
                .getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(Direction.EAST, planned.get(1).state()
                .getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    @Test
    void borderMarkersRejectOutwardOnlySupport() {
        LightPool wall = wallPool();
        BlockPos westBorder = pos(0, 70, 1);
        BlockPos eastBorder = pos(15, 70, 1);
        Map<BlockPos, BlockState> surroundings = Map.of(
                westBorder.west(), Blocks.STONE.defaultBlockState(),
                eastBorder.east(), Blocks.STONE.defaultBlockState());

        List<DeferredLightPlacer.Planned> planned = DeferredLightPlacer.plan(
                OWNER_X, OWNER_Z, 19L,
                List.of(new LightTodoQueue.Todo(westBorder, source(wall)),
                        new LightTodoQueue.Todo(eastBorder, source(wall))),
                candidate -> surroundings.getOrDefault(candidate, Blocks.AIR.defaultBlockState()),
                (marker, supportDirection, stateAt) ->
                        !stateAt.apply(marker.relative(supportDirection)).isAir(),
                (marker, attempt, stateAt) -> true);

        assertTrue(planned.isEmpty());
    }

    @Test
    void adjacentQueuedMarkersArePlannedAgainstAirInEitherOrder() {
        BlockPos freeMarker = pos(5, 70, 5);
        BlockPos wallMarker = freeMarker.east();
        LightPool free = pool(List.of(), List.of(), List.of(),
                List.of(entry(1, "minecraft:glowstone")));
        LightPool wall = wallPool();
        List<LightTodoQueue.Todo> forward = List.of(
                new LightTodoQueue.Todo(freeMarker, source(free)),
                new LightTodoQueue.Todo(wallMarker, source(wall)));
        List<LightTodoQueue.Todo> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        Function<BlockPos, BlockState> overwrittenMarkerState = candidate ->
                candidate.equals(freeMarker) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();

        List<DeferredLightPlacer.Planned> forwardPlan = DeferredLightPlacer.plan(
                OWNER_X, OWNER_Z, 23L, forward, overwrittenMarkerState,
                (marker, supportDirection, stateAt) ->
                        !stateAt.apply(marker.relative(supportDirection)).isAir(),
                (marker, attempt, stateAt) -> true);
        List<DeferredLightPlacer.Planned> reversePlan = DeferredLightPlacer.plan(
                OWNER_X, OWNER_Z, 23L, reverse, overwrittenMarkerState,
                (marker, supportDirection, stateAt) ->
                        !stateAt.apply(marker.relative(supportDirection)).isAir(),
                (marker, attempt, stateAt) -> true);

        assertEquals(Map.of(freeMarker, Blocks.GLOWSTONE.defaultBlockState()), byPosition(forwardPlan));
        assertEquals(byPosition(forwardPlan), byPosition(reversePlan));
    }

    @Test
    void sharedVariantsAreStableAcrossDensityAndTodoOrder() {
        long seed = 31L;
        LightPool free = pool(List.of(), List.of(), List.of(), List.of(
                entry(1, "minecraft:glowstone"),
                entry(1, "minecraft:sea_lantern")));
        List<LightTodoQueue.Todo> all = IntStream.range(0, 64)
                .mapToObj(y -> new BlockPos((OWNER_X << 4) + 7, y, (OWNER_Z << 4) + 7))
                .map(marker -> new LightTodoQueue.Todo(marker, source(free)))
                .toList();
        List<LightTodoQueue.Todo> low = all.stream()
                .filter(todo -> DensitySelector.lighting(seed, todo.pos(), 0.25f))
                .toList();
        List<LightTodoQueue.Todo> high = all.stream()
                .filter(todo -> DensitySelector.lighting(seed, todo.pos(), 0.75f))
                .toList();
        List<LightTodoQueue.Todo> reversedHigh = new ArrayList<>(high);
        Collections.reverse(reversedHigh);

        Map<BlockPos, BlockState> lowPlan = byPosition(plan(low, (pos, fallback) -> fallback));
        Map<BlockPos, BlockState> highPlan = byPosition(plan(high, (pos, fallback) -> fallback));
        Map<BlockPos, BlockState> reversedPlan = byPosition(plan(reversedHigh, (pos, fallback) -> fallback));

        assertTrue(highPlan.keySet().containsAll(lowPlan.keySet()));
        lowPlan.forEach((marker, state) -> assertEquals(state, highPlan.get(marker)));
        assertEquals(highPlan, reversedPlan);
    }

    private static List<DeferredLightPlacer.Planned> plan(
            List<LightTodoQueue.Todo> todos,
            java.util.function.BiFunction<BlockPos, BlockState, BlockState> stateAt) {
        return DeferredLightPlacer.plan(
                OWNER_X, OWNER_Z, 31L, todos,
                pos -> stateAt.apply(pos, Blocks.AIR.defaultBlockState()),
                (marker, supportDirection, snapshot) ->
                        !snapshot.apply(marker.relative(supportDirection)).isAir(),
                (marker, attempt, snapshot) -> true);
    }

    private static Map<BlockPos, BlockState> byPosition(List<DeferredLightPlacer.Planned> planned) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        planned.forEach(light -> result.put(light.pos(), light.state()));
        return result;
    }

    /** A socket with no replacement: what an entry that names no {@code unlit} compiles to. */
    private static LightSource source(LightPool pool) {
        return new LightSource(pool, BlockChoice.AIR);
    }

    private static LightPool wallPool() {
        return pool(List.of(),
                List.of(entry(1, "minecraft:wall_torch[facing=north]")),
                List.of(), List.of());
    }

    private static LightPool pool(List<LightSourceSettings.Entry> floor,
                                  List<LightSourceSettings.Entry> wall,
                                  List<LightSourceSettings.Entry> ceiling,
                                  List<LightSourceSettings.Entry> free) {
        return LightPool.compile(BuiltInRegistries.BLOCK, PALETTE_ID, 'L', new LightSourceSettings(floor, wall, ceiling, free, null, null));
    }

    private static LightSourceSettings.Entry entry(int weight, String block) {
        return new LightSourceSettings.Entry(weight, block);
    }

    private static BlockPos pos(int localX, int y, int localZ) {
        return new BlockPos((OWNER_X << 4) + localX, y, (OWNER_Z << 4) + localZ);
    }
}
