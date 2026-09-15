package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Finalize real marker NBT, notify without block writes, clean up, then let Minecraft load it. */
class GenerationUpdatesTest {
    private static final ChunkPos OWNER = new ChunkPos(-3, 2);
    private static final BlockPos AT = new BlockPos(-43, 80, 37);
    private static final WriteWindow WHOLE_WORLD = new WriteWindow(TestChunk.HEIGHT.getMinY(), TestChunk.HEIGHT.getMaxY());
    private static final Identifier LOOT = Identifier.parse("minecraft:chests/simple_dungeon");
    private static RegistryAccess registries;
    private static Preset preset;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        PresetDraft draft = new PresetDraft(Identifier.parse("urbex:generation_updates"));
        draft.LOOT_DENSITY = 1;
        draft.SPAWNER_DENSITY = 1;
        preset = draft.resolve();
    }

    @Test
    void finalPendingLootAndAuthoredDataSurviveNotificationCleanupAndMinecraftLoading() {
        for (boolean instantiate : new boolean[]{false, true}) {
            CompoundTag authored = new CompoundTag();
            authored.putString("CustomName", "Supplies");
            authored.putLong("LootTableSeed", 91L);
            CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.BARREL.defaultBlockState(),
                    Palette.Info.of(null, "urbex:loot", null, authored));
            Fixture f = finalized(material, AT);
            CompoundTag pending = f.chunk.getBlockEntityNbt(AT);
            CompoundTag original = pending.copy();
            assertEquals(LOOT.toString(), pending.getStringOr("LootTable", ""));
            assertEquals(91L, pending.getLongOr("LootTableSeed", -1));
            BlockEntity existing = instantiate ? loadAndInstall(f, AT) : null;
            CompoundTag pendingBeforeNotify = f.chunk.getBlockEntityNbt(AT);
            CompoundTag liveBefore = existing == null ? null : existing.saveWithFullMetadata(registries);

            List<PoiChange> poi = notify(f, AT, Block.UPDATE_CLIENTS, WHOLE_WORLD);
            assertEquals(refreshPair(AT, material.state()), poi);
            assertSame(pendingBeforeNotify, f.chunk.getBlockEntityNbt(AT),
                    "notification preserves pending storage, including its absence after instantiation");
            assertEquals(original, pending);
            assertSame(existing, f.chunk.getBlockEntity(AT), "an already instantiated entity keeps its identity");
            if (existing != null) assertEquals(liveBefore, existing.saveWithFullMetadata(registries));
            Parts.forgetBlockEntities(f.chunk);
            assertSame(pendingBeforeNotify, f.chunk.getBlockEntityNbt(AT), "valid pending data survives pipeline cleanup");
            CompoundTag saved = f.chunk.getBlockEntityNbtForSaving(AT, registries);
            assertNotNull(saved);
            assertNotEquals("DUMMY", saved.getStringOr("id", ""));
            BarrelBlockEntity loaded = assertInstanceOf(BarrelBlockEntity.class,
                    BlockEntity.loadStatic(AT, f.chunk.getBlockState(AT), saved, registries));
            assertEquals(ResourceKey.create(Registries.LOOT_TABLE, LOOT), loaded.getLootTable());
            assertEquals(91L, loaded.getLootTableSeed());
            assertNotNull(loaded.getCustomName());
            assertEquals("Supplies", loaded.getCustomName().getString());
        }
    }

    @Test
    void finalSpawnerMobAndAuthoredDelaySurvivePendingAndInstantiatedNotificationPaths() {
        for (boolean instantiate : new boolean[]{false, true}) {
            CompoundTag authored = new CompoundTag();
            authored.putInt("Delay", 40);
            CompiledPalette.Placed material = new CompiledPalette.Placed(Blocks.SPAWNER.defaultBlockState(),
                    Palette.Info.of("urbex:mobs", null, null, authored));
            Fixture f = finalized(material, AT);
            CompoundTag pending = f.chunk.getBlockEntityNbt(AT);
            CompoundTag original = pending.copy();
            BlockEntity existing = instantiate ? loadAndInstall(f, AT) : null;
            CompoundTag pendingBeforeNotify = f.chunk.getBlockEntityNbt(AT);
            CompoundTag liveBefore = existing == null ? null : existing.saveWithFullMetadata(registries);

            assertEquals(refreshPair(AT, material.state()), notify(f, AT, Block.UPDATE_CLIENTS, WHOLE_WORLD));
            assertSame(pendingBeforeNotify, f.chunk.getBlockEntityNbt(AT));
            assertEquals(original, pending);
            assertSame(existing, f.chunk.getBlockEntity(AT));
            if (existing != null) assertEquals(liveBefore, existing.saveWithFullMetadata(registries));
            Parts.forgetBlockEntities(f.chunk);
            assertSame(pendingBeforeNotify, f.chunk.getBlockEntityNbt(AT));
            CompoundTag saved = f.chunk.getBlockEntityNbtForSaving(AT, registries);
            assertNotNull(saved);
            SpawnerBlockEntity loaded = assertInstanceOf(SpawnerBlockEntity.class,
                    BlockEntity.loadStatic(AT, f.chunk.getBlockState(AT), saved, registries));
            CompoundTag reserialized = loaded.saveWithFullMetadata(registries);
            SpawnData spawn = SpawnData.CODEC.parse(NbtOps.INSTANCE, reserialized.get("SpawnData")).getOrThrow();
            assertEquals("minecraft:skeleton", spawn.getEntityToSpawn().getStringOr("id", ""));
            assertEquals(40, reserialized.getIntOr("Delay", -1));
            assertEquals(AT.getX(), reserialized.getIntOr("x", 0));
            assertEquals(AT.getY(), reserialized.getIntOr("y", 0));
            assertEquals(AT.getZ(), reserialized.getIntOr("z", 0));
        }
    }

    @Test
    void callbacksUseTheFinalStateAndCannotRestoreADestroyedOrReplacedMarker() {
        CompiledPalette.Placed original = new CompiledPalette.Placed(Blocks.BARREL.defaultBlockState(),
                Palette.Info.of(null, "urbex:loot", null, new CompoundTag()));
        for (BlockState replacement : List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState())) {
            GuardedChunk chunk = new GuardedChunk();
            LevelAccessor world = worldFor(chunk);
            ChunkDriver driver = new ChunkDriver();
            driver.setPrimer(world, chunk);
            driver.currentAbsolute(AT).block(original);
            driver.flushToChunk(chunk);
            driver.currentAbsolute(AT).block(replacement);
            driver.actuallyGenerate(chunk, 0L, (target, pos, actual, material, origin) ->
                    fail("the overwritten marker has no surviving decorators"));
            Fixture f = new Fixture(chunk, world);
            List<PoiChange> poi = notify(f, AT, Block.UPDATE_CLIENTS, WHOLE_WORLD);
            assertEquals(replacement.isAir() ? List.of() : refreshPair(AT, replacement), poi);
            Parts.forgetBlockEntities(chunk);
            assertSame(replacement, chunk.getBlockState(AT));
            assertNull(chunk.getBlockEntityNbt(AT));
            assertTrue(chunk.notifiedPostProcess.isEmpty());
        }
    }

    @Test
    void actualStatePostProcessingUsesItsReturnedPositionAndHonorsTheKnownShapeFlag() {
        for (BlockState state : List.of(Blocks.RED_MUSHROOM.defaultBlockState(),
                Blocks.SOUL_SAND.defaultBlockState(), Blocks.STONE.defaultBlockState())) {
            for (int flags : new int[]{Block.UPDATE_CLIENTS, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE}) {
                Fixture f = finalized(new CompiledPalette.Placed(state, null), AT);
                BlockPos requested = state.getPostProcessPos(f.world, AT);
                if (state.is(Blocks.RED_MUSHROOM)) assertEquals(AT, requested);
                if (state.is(Blocks.SOUL_SAND)) assertEquals(AT.above(), requested);
                if (state.is(Blocks.STONE)) assertNull(requested);
                assertEquals(refreshPair(AT, state), notify(f, AT, flags, WHOLE_WORLD));
                List<BlockPos> expected = (flags & Block.UPDATE_KNOWN_SHAPE) == 0 && requested != null
                        ? List.of(requested) : List.of();
                assertEquals(expected, f.chunk.notifiedPostProcess);
                if (!expected.isEmpty()) {
                    assertTrue(f.chunk.getPostProcessing()[f.chunk.getSectionIndex(requested.getY())]
                            .contains(ProtoChunk.packOffsetCoordinates(requested)),
                            "the production chunk's real postprocessing list receives the requested position");
                }
                assertSame(state, f.chunk.getBlockState(AT));
            }
        }
    }

    @Test
    void sourceGuardsRejectOtherChunksAndOutOfWindowPositionsBeforeReadingState() {
        Fixture f = finalized(new CompiledPalette.Placed(Blocks.SOUL_SAND.defaultBlockState(), null), AT);
        for (BlockPos rejected : List.of(AT.offset(16, 0, 0), AT.offset(0, 0, -16), AT.below(), AT.above())) {
            int readsBefore = f.chunk.guardedReads;
            assertTrue(notify(f, rejected, Block.UPDATE_CLIENTS, new WriteWindow(80, 80)).isEmpty());
            assertEquals(readsBefore, f.chunk.guardedReads, "guard rejects " + rejected + " before chunk reads");
            assertTrue(f.chunk.notifiedPostProcess.isEmpty());
        }
        for (int y : new int[]{TestChunk.HEIGHT.getMinY() - 1, TestChunk.HEIGHT.getMaxY() + 1}) {
            int readsBefore = f.chunk.guardedReads;
            assertTrue(notify(f, new BlockPos(AT.getX(), y, AT.getZ()), Block.UPDATE_CLIENTS,
                    new WriteWindow(Integer.MIN_VALUE, Integer.MAX_VALUE)).isEmpty());
            assertEquals(readsBefore, f.chunk.guardedReads);
        }
    }

    @Test
    void shiftedPostprocessingStaysInsideTheWriteWindowAndActualWorldHeight() {
        for (int y : new int[]{80, TestChunk.HEIGHT.getMaxY()}) {
            BlockPos anchor = new BlockPos(AT.getX(), y, AT.getZ());
            BlockState sand = Blocks.SOUL_SAND.defaultBlockState();
            Fixture f = finalized(new CompiledPalette.Placed(sand, null), anchor);
            WriteWindow window = y == 80 ? new WriteWindow(80, 80)
                    : new WriteWindow(Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertEquals(anchor.above(), sand.getPostProcessPos(f.world, anchor));
            assertEquals(refreshPair(anchor, sand), notify(f, anchor, Block.UPDATE_CLIENTS, window));
            assertTrue(f.chunk.notifiedPostProcess.isEmpty(), "the returned position lies outside the allowed band");
            assertSame(sand, f.chunk.getBlockState(anchor));
        }
    }

    private static Fixture finalized(CompiledPalette.Placed material, BlockPos position) {
        GuardedChunk chunk = new GuardedChunk();
        LevelAccessor world = worldFor(chunk);
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(world, chunk);
        driver.currentAbsolute(position).block(material);
        driver.actuallyGenerate(chunk, 0L, (target, pos, actual, source, origin) ->
                MarkerDecorations.apply(target, pos, actual, source, preset, 231L,
                        (pool, random, effect) -> "spawner".equals(effect)
                                ? Identifier.parse("minecraft:skeleton") : LOOT));
        return new Fixture(chunk, world);
    }

    private static BlockEntity loadAndInstall(Fixture f, BlockPos position) {
        BlockEntity entity = BlockEntity.loadStatic(position, f.chunk.getBlockState(position),
                f.chunk.getBlockEntityNbt(position), registries);
        assertNotNull(entity);
        f.chunk.setBlockEntity(entity);
        return entity;
    }

    private static List<PoiChange> notify(Fixture f, BlockPos position, int flags, WriteWindow window) {
        List<PoiChange> changes = new ArrayList<>();
        f.chunk.forbidMutations = true;
        GenerationUpdates.apply(f.world, f.chunk, window, position, flags,
                (pos, before, after) -> changes.add(new PoiChange(pos.immutable(), before, after)));
        return changes;
    }

    private static List<PoiChange> refreshPair(BlockPos pos, BlockState actual) {
        return List.of(new PoiChange(pos, actual, Blocks.AIR.defaultBlockState()),
                new PoiChange(pos, Blocks.AIR.defaultBlockState(), actual));
    }

    private record PoiChange(BlockPos pos, BlockState before, BlockState after) { }
    private record Fixture(GuardedChunk chunk, LevelAccessor world) { }

    private static LevelAccessor worldFor(GuardedChunk chunk) {
        LevelAccessor delegate = TestChunk.levelFor(chunk);
        Set<String> mutations = Set.of("setBlock", "setBlockState", "removeBlock", "destroyBlock",
                "setBlockEntity", "removeBlockEntity", "setBlockEntityNbt");
        return (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
                new Class<?>[]{LevelAccessor.class}, (proxy, method, args) -> {
                    if (mutations.contains(method.getName())) {
                        throw new AssertionError("generation notification attempted world mutation: " + method.getName());
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Real chunk storage; mutation APIs become tripwires only after production finalization. */
    private static final class GuardedChunk extends ProtoChunk {
        private boolean forbidMutations;
        private int guardedReads;
        private final List<BlockPos> notifiedPostProcess = new ArrayList<>();

        private GuardedChunk() {
            super(OWNER, UpgradeData.EMPTY, TestChunk.HEIGHT, TestChunk.containerFactory(), null);
        }

        private void mutation(String operation) {
            if (forbidMutations) throw new AssertionError("generation notification attempted chunk mutation: " + operation);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (forbidMutations) guardedReads++;
            return super.getBlockState(pos);
        }

        @Override
        public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
            mutation("setBlockState");
            return super.setBlockState(pos, state, flags);
        }

        @Override
        public void setBlockEntity(BlockEntity entity) {
            mutation("setBlockEntity");
            super.setBlockEntity(entity);
        }

        @Override
        public void removeBlockEntity(BlockPos pos) {
            mutation("removeBlockEntity");
            super.removeBlockEntity(pos);
        }

        @Override
        public void setBlockEntityNbt(CompoundTag tag) {
            mutation("setBlockEntityNbt");
            super.setBlockEntityNbt(tag);
        }

        @Override
        public void markPosForPostProcessing(BlockPos pos) {
            if (forbidMutations) notifiedPostProcess.add(pos.immutable());
            super.markPosForPostProcessing(pos);
        }
    }
}
