package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
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
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Compile sockets, accept placeholders, modify the chunk, then plan and commit only survivors. */
class DeferredSocketOwnershipTest {
    private static final long SEED = 228;
    private static final ChunkPos OWNER = new ChunkPos(-3, -2);
    private static final PlacementOrigin ORIGIN = new PlacementOrigin(null, "urbex:socket_part");
    private static CompiledPalette palette;
    private static Preset preset;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        PresetDraft draft = new PresetDraft(Identifier.parse("urbex:socket_ownership"));
        draft.LIGHTING_DENSITY = 1;
        draft.LOOT_DENSITY = 1;
        preset = draft.resolve();
        palette = compile("""
                {"version":2,"palette":{
                  "L":{"kind":"light_socket","free":[{"weight":1,"block":"minecraft:campfire",
                    "traits":{"urbex:light":{"unlit":"minecraft:barrel"},
                      "urbex:block_entity":{"nbt":{"Custom":228}}}}]},
                  "W":{"kind":"light_socket","wall":[{"weight":1,"block":"minecraft:wall_torch"}]},
                  "R":{"kind":"light_socket","free":[
                    {"weight":1,"block":"minecraft:campfire"},
                    {"weight":1,"block":"minecraft:soul_campfire"}]}
                }}
                """);
    }

    @Test
    void acceptedReplacementsCancelSocketsEvenWhenAirIsUnchangedAndTheBufferWasFlushed() {
        for (int path = 0; path < 8; path++) {
            Fixture f = fixture();
            BlockPos replaced = pos(3, 80, 4);
            BlockPos survivor = pos(9, 80, 4);
            socket(f, replaced, 'L', true);
            socket(f, survivor, 'L', true);
            f.driver.flushToChunk(f.chunk);
            f.driver.currentAbsolute(replaced);
            BlockState expected = path == 0 || path == 3 || path == 5 || path == 7
                    ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            switch (path) {
                case 0 -> f.driver.block(expected);
                case 1 -> f.driver.block(expected);
                case 2 -> f.driver.add(expected);
                case 3 -> f.driver.setBlock(replaced.getX(), replaced.getY(), replaced.getZ(), expected);
                case 4 -> f.driver.setBlockRangeToAir(3, 79, 4, 82);
                case 5 -> f.driver.setBlockRange(3, 79, 4, 82, expected);
                case 6 -> f.driver.setBlockRange(3, 79, 4, 82, new CompiledPalette.Placed(expected, null));
                case 7 -> f.driver.setBlockRange(3, 79, 4, 82, expected, old -> true);
                default -> throw new AssertionError(path);
            }
            List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
            assertEquals(List.of(survivor), live.stream().map(LightTodoQueue.Todo::pos).toList(),
                    "accepted overwrite path " + path);
            commit(f, plan(f, live));
            assertEquals(expected, f.chunk.getBlockState(replaced), "stale socket must not replace path " + path);
            assertNull(f.chunk.getBlockEntityNbt(replaced));
            assertTrue(f.chunk.getBlockState(survivor).is(Blocks.CAMPFIRE));
            assertEquals(228, f.chunk.getBlockEntityNbt(survivor).getIntOr("Custom", -1));
        }
    }

    @Test
    void repeatedIdenticalSourceAndOriginHaveOneOwnerWithTheLatestLightingDecision() {
        Fixture f = fixture();
        BlockPos marker = pos(3, 80, 4);
        CompiledPalette.Placed source = source('L', marker);
        f.driver.currentAbsolute(marker).blockLightSocket(source, true, ORIGIN);
        f.driver.blockLightSocket(source, true, ORIGIN);
        f.driver.flushToChunk(f.chunk);
        f.driver.blockLightSocket(source, false, ORIGIN);
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(1, live.size());
        assertSame(source.info().lightSource(), live.getFirst().source());
        assertSame(ORIGIN, live.getFirst().origin());
        assertFalse(live.getFirst().lit());
        List<DeferredLightPlacer.Planned> planned = plan(f, live);
        assertEquals(1, planned.size());
        assertSame(ORIGIN, planned.getFirst().origin());
        commit(f, planned);
        assertTrue(f.chunk.getBlockState(marker).is(Blocks.BARREL), "the latest unlit choice owns the placeholder");
        assertEquals(228, f.chunk.getBlockEntityNbt(marker).getIntOr("Custom", -1));
    }

    @Test
    void aLaterSocketReplacesBothTheEarlierSourceAndItsPartOrigin() {
        Fixture f = fixture();
        BlockPos marker = pos(3, 80, 4);
        socket(f, marker, 'L', true);
        f.driver.flushToChunk(f.chunk);
        PlacementOrigin laterOrigin = new PlacementOrigin(null, "urbex:later_part");
        CompiledPalette.Placed later = source('R', marker);
        f.driver.currentAbsolute(marker).blockLightSocket(later, true, laterOrigin);
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(1, live.size());
        assertSame(later.info().lightSource(), live.getFirst().source());
        assertSame(laterOrigin, live.getFirst().origin());
        List<DeferredLightPlacer.Planned> planned = plan(f, live);
        assertEquals(1, planned.size());
        assertSame(laterOrigin, planned.getFirst().origin());
        commit(f, planned);
        assertEquals(planned.getFirst().state(), f.chunk.getBlockState(marker));
        assertNull(f.chunk.getBlockEntityNbt(marker), "the first socket's NBT cannot decorate the later source");
    }

    @Test
    void transparentAndPredicateRejectedWritesPreserveTheSurvivingSocket() {
        Fixture f = fixture();
        BlockPos marker = pos(3, 80, 4);
        socket(f, marker, 'L', true);
        f.driver.currentAbsolute(marker).block((BlockState) null);
        f.driver.block(Blocks.STRUCTURE_VOID.defaultBlockState());
        f.driver.setBlockRange(3, 79, 4, 82, Blocks.STONE.defaultBlockState(), old -> false);
        f.driver.setBlockRange(3, 79, 4, 82, Blocks.STRUCTURE_VOID.defaultBlockState());
        // fillWhere intentionally skips equal states before calling its predicate; that existing
        // no-op is distinct from the accepted same-air writes exercised above.
        f.driver.setBlockRangeToAir(3, 79, 4, 82, old -> true);
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(List.of(marker), live.stream().map(LightTodoQueue.Todo::pos).toList());
        commit(f, plan(f, live));
        assertTrue(f.chunk.getBlockState(marker).is(Blocks.CAMPFIRE));
    }

    @Test
    void canceledSocketReplacementIsVisibleAsSupportForAnotherSurvivingSocket() {
        Fixture f = fixture();
        BlockPos support = pos(4, 80, 4);
        BlockPos lamp = support.east();
        socket(f, support, 'L', true);
        socket(f, lamp, 'W', true);
        f.driver.flushToChunk(f.chunk);
        f.driver.currentAbsolute(support).block(Blocks.STONE.defaultBlockState());
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(List.of(lamp), live.stream().map(LightTodoQueue.Todo::pos).toList());
        List<DeferredLightPlacer.Planned> planned = plan(f, live);
        assertEquals(1, planned.size(), "the canceled position must no longer be masked as air in the support snapshot");
        assertEquals(Direction.EAST, planned.getFirst().state().getValue(WallTorchBlock.FACING));
        // The snapshot assertions above deliberately read buffered support. Flush it before final
        // shaping: vanilla wall-torch updateShape reads the driver's underlying region, a separate
        // preexisting shape-read issue that otherwise removes a torch against unflushed support.
        f.driver.flushToChunk(f.chunk);
        commit(f, planned);
        assertTrue(f.chunk.getBlockState(support).is(Blocks.STONE));
        assertTrue(f.chunk.getBlockState(lamp).is(Blocks.WALL_TORCH));
    }

    @Test
    void clippedAndCrossChunkRequestsNeverAdmitOrClearAnAliasedBlock() {
        Fixture f = fixture(80, 80);
        BlockPos lower = pos(0, 79, 15);
        BlockPos upper = pos(15, 81, 0);
        f.chunk.setBlockState(lower, Blocks.GOLD_BLOCK.defaultBlockState(), 0);
        f.chunk.setBlockState(upper, Blocks.GOLD_BLOCK.defaultBlockState(), 0);
        socket(f, lower, 'L', true);
        socket(f, upper, 'L', true);
        BlockPos boundary = pos(15, 80, 15);
        BlockPos aliased = pos(0, 80, 15);
        f.driver.currentAbsolute(aliased).block(Blocks.GOLD_BLOCK.defaultBlockState());
        BlockPos wrongChunk = boundary.east();
        f.driver.currentAbsolute(wrongChunk);
        assertThrows(IllegalArgumentException.class,
                () -> f.driver.blockLightSocket(source('L', wrongChunk), true, ORIGIN));
        socket(f, boundary, 'L', true);
        f.driver.current(2, 82, 2); // Neither cursor movement nor rejected earlier writes can relocate the todo.
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(List.of(boundary), live.stream().map(LightTodoQueue.Todo::pos).toList());
        commit(f, plan(f, live));
        for (BlockPos untouched : List.of(lower, upper, aliased)) {
            assertTrue(f.chunk.getBlockState(untouched).is(Blocks.GOLD_BLOCK), untouched.toString());
        }
        assertTrue(f.chunk.getBlockState(boundary).is(Blocks.CAMPFIRE));
    }

    @Test
    void materialPolicyRejectionPreservesOwnershipButChangedPlaceholdersCancelAndCannotLeak() {
        Fixture f = fixture();
        BlockPos preserved = pos(3, 80, 4);
        BlockPos canceled = pos(9, 80, 4);
        BlockPos unrelated = pos(6, 80, 4);
        socket(f, preserved, 'L', true);
        socket(f, canceled, 'L', true);
        f.driver.setMaterialPolicy((x, y, z, actual, material, origin) -> {
            if (x == preserved.getX()) return Blocks.STRUCTURE_VOID.defaultBlockState();
            if (x == canceled.getX()) return Blocks.CAVE_AIR.defaultBlockState();
            return actual;
        });
        socket(f, preserved, 'L', false);
        socket(f, canceled, 'L', false);
        f.driver.currentAbsolute(unrelated).block(new CompiledPalette.Placed(Blocks.STONE.defaultBlockState(), null));
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(List.of(preserved), live.stream().map(LightTodoQueue.Todo::pos).toList());
        assertTrue(live.getFirst().lit(), "the rejected unlit placeholder cannot replace the original lit owner");
        assertTrue(f.driver.getBlock().is(Blocks.STONE), "the pending socket must not attach to the next material write");
        // Admission policy was the subject of the placeholder write, not deferred candidate placement.
        f.driver.setMaterialPolicy(null);
        commit(f, plan(f, live));
        assertTrue(f.chunk.getBlockState(preserved).is(Blocks.CAMPFIRE));
        assertTrue(f.chunk.getBlockState(canceled).is(Blocks.CAVE_AIR));
        assertTrue(f.chunk.getBlockState(unrelated).is(Blocks.STONE));
    }

    @Test
    void shapeOnlyWritesPreserveOwnershipButARealShapeReplacementCancelsIt() throws Exception {
        Fixture f = fixture();
        BlockPos preserved = pos(3, 80, 4);
        BlockPos replaced = pos(9, 80, 4);
        socket(f, preserved, 'L', true);
        socket(f, replaced, 'L', true);
        // A placeholder is air and has no naturally changing connection property. Invoke the exact
        // private callback used by BlockShaper to exercise ownership without exposing a new API.
        Method shaped = ChunkDriver.class.getDeclaredMethod("setShapedBlock", BlockPos.class, BlockState.class);
        shaped.setAccessible(true);
        shaped.invoke(f.driver, preserved, Blocks.AIR.defaultBlockState());
        shaped.invoke(f.driver, replaced, Blocks.STONE.defaultBlockState());
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(List.of(preserved), live.stream().map(LightTodoQueue.Todo::pos).toList());
        commit(f, plan(f, live));
        assertTrue(f.chunk.getBlockState(preserved).is(Blocks.CAMPFIRE));
        assertTrue(f.chunk.getBlockState(replaced).is(Blocks.STONE));
    }

    @Test
    void closedQueuesRejectBeforeWritingAndResetAndPublicationDiscardUnfinishedOwnership() {
        Fixture f = fixture();
        BlockPos marker = pos(3, 80, 4);
        BlockPos guarded = pos(9, 80, 4);
        socket(f, marker, 'L', true);
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        f.driver.currentAbsolute(guarded).block(Blocks.STONE.defaultBlockState());
        assertThrows(IllegalStateException.class,
                () -> f.driver.blockLightSocket(source('L', guarded), true, ORIGIN));
        assertTrue(f.driver.getBlock().is(Blocks.STONE), "closed-queue admission fails before clearing the block");
        commit(f, plan(f, live));
        f.driver.currentAbsolute(marker);
        assertThrows(IllegalStateException.class,
                () -> f.driver.blockLightSocket(source('L', marker), true, ORIGIN));

        Fixture unfinished = fixture();
        socket(unfinished, marker, 'L', true);
        ProtoChunk next = TestChunk.emptyChunk(OWNER);
        unfinished.driver.setPrimer(TestChunk.levelFor(next), next);
        assertThrows(IllegalStateException.class, unfinished.queue::closeAndDrain);
        unfinished.driver.currentAbsolute(marker);
        assertThrows(IllegalStateException.class,
                () -> unfinished.driver.blockLightSocket(source('L', marker), true, ORIGIN));
        LightTodoQueue nextQueue = new LightTodoQueue(OWNER.x(), OWNER.z());
        unfinished.driver.setLightTodoQueue(nextQueue);
        unfinished.driver.blockLightSocket(source('L', marker), true, ORIGIN);
        unfinished.driver.actuallyGenerate(next); // Abandoned deferred work cannot survive publication.
        assertThrows(IllegalStateException.class, nextQueue::closeAndDrain);
        assertThrows(IllegalStateException.class,
                () -> unfinished.driver.blockLightSocket(source('L', marker), true, ORIGIN));
        assertTrue(next.getBlockState(marker).isAir());
    }

    @Test
    void cancelingAndReinsertingWeightedSocketsDoesNotMakeSelectionDependOnQueueOrder() {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = 2; x < 14; x += 2) {
            for (int z = 2; z < 14; z += 2) positions.add(pos(x, 80, z));
        }
        Map<BlockPos, BlockState> forward = weightedRun(positions);
        Collections.reverse(positions);
        assertEquals(forward, weightedRun(positions));
        assertEquals(Set.of(Blocks.CAMPFIRE.defaultBlockState(), Blocks.SOUL_CAMPFIRE.defaultBlockState()),
                Set.copyOf(forward.values()), "the fixture must exercise both weighted choices");
    }

    private static Map<BlockPos, BlockState> weightedRun(List<BlockPos> positions) {
        Fixture f = fixture();
        for (BlockPos position : positions) {
            socket(f, position, 'R', true);
            f.driver.block(Blocks.AIR.defaultBlockState());
            socket(f, position, 'R', true);
        }
        List<LightTodoQueue.Todo> live = f.queue.closeAndDrain();
        assertEquals(positions.size(), live.size());
        commit(f, plan(f, live));
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (BlockPos position : positions) result.put(position, f.chunk.getBlockState(position));
        return result;
    }

    private static List<DeferredLightPlacer.Planned> plan(Fixture f, List<LightTodoQueue.Todo> live) {
        return DeferredLightPlacer.plan(OWNER.x(), OWNER.z(), SEED, preset, live, f.driver::getBlockAt,
                (marker, direction, stateAt) -> stateAt.apply(marker.relative(direction)).is(Blocks.STONE),
                (marker, attempt, stateAt) -> true);
    }

    private static void commit(Fixture f, List<DeferredLightPlacer.Planned> planned) {
        for (DeferredLightPlacer.Planned light : planned) {
            f.driver.currentAbsolute(light.pos()).block(light.state(), light.material(), light.origin());
        }
        f.driver.actuallyGenerate(f.chunk, 0L, (chunk, position, actual, material, origin) -> {
            assertSame(ORIGIN, origin);
            MarkerDecorations.applyNbt(chunk, position, actual, material.info().tag(), null);
        });
    }

    private static void socket(Fixture f, BlockPos position, char marker, boolean lit) {
        f.driver.currentAbsolute(position).blockLightSocket(source(marker, position), lit, ORIGIN);
    }

    private static CompiledPalette.Placed source(char marker, BlockPos position) {
        return palette.placedAt(marker, SEED, position.getX(), position.getY(), position.getZ());
    }

    private static Fixture fixture() {
        return fixture(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static Fixture fixture(int bottom, int top) {
        ProtoChunk chunk = TestChunk.emptyChunk(OWNER);
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk, bottom, top);
        LightTodoQueue queue = new LightTodoQueue(OWNER.x(), OWNER.z());
        driver.setLightTodoQueue(queue);
        return new Fixture(chunk, driver, queue);
    }

    private record Fixture(ProtoChunk chunk, ChunkDriver driver, LightTodoQueue queue) { }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos((OWNER.x() << 4) + x, y, (OWNER.z() << 4) + z);
    }

    private static CompiledPalette compile(String json) {
        PaletteV2Definition definition = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        Diagnostics diagnostics = new Diagnostics();
        CompiledV2Palette compiled = NodeResolver.resolve(definition, diagnostics)
                .flatMap(resolved -> CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("minecraft", "urbex")),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                        "'urbex:socket_ownership'", diagnostics))
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("compile failed")));
        return new CompiledPalette(Palette.version2(Identifier.parse("urbex:socket_ownership"), compiled));
    }
}
