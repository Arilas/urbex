package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Condition;
import dev.krona.urbex.worldgen.lost.cityassets.ConditionContext;
import dev.krona.urbex.worldgen.lost.cityassets.MarkerCapabilities;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import javax.annotation.Nullable;
import java.util.Optional;

/** Applies decorators to the material that survives all generation writes at a position. */
public final class MarkerDecorations {
    private MarkerDecorations() {
    }

    /** Spawner admission affects geometry, so it runs before the driver accepts the write. */
    public static BlockState prepareState(ChunkGenContext ctx, int x, int y, int z,
                                          BlockState actual, CompiledPalette.Placed material,
                                          PlacementOrigin origin) {
        ChunkPlan owner = origin == null ? ctx.info : origin.owner();
        return prepareState(owner.profile, ctx.seed, x, y, z, actual, material);
    }

    static BlockState prepareState(Preset preset, long seed, int x, int y, int z,
                                    BlockState actual, CompiledPalette.Placed material) {
        Palette.Info info = material.info();
        if (info != null && info.mobId() != null && !info.mobId().isEmpty()
                && MarkerCapabilities.supportsSpawner(actual)
                && !SpecialMarkerPolicy.generateSpawner(seed, new BlockPos(x, y, z), preset)) {
            return Blocks.AIR.defaultBlockState();
        }
        return actual;
    }

    /** Called after block corrections and old block-entity cleanup, before the driver publishes. */
    public static void apply(ChunkGenContext ctx, ChunkAccess chunk, BlockPos pos, BlockState actual,
                             CompiledPalette.Placed material, PlacementOrigin origin) {
        Palette.Info info = material.info();
        if (info == null) {
            return;
        }
        PlacementOrigin source = origin == null ? ctx.proceduralOrigin : origin;
        ChunkPlan owner = source.owner();
        apply(chunk, pos, actual, material, owner.profile, ctx.seed, (pool, random, effect) -> {
            Identifier value = selectCondition(ctx.region, ctx.provider, owner,
                    new ChunkPlan.ConditionTodo(pool, source.part(), owner), pos, random, effect);
            if (value == null) {
                ctx.warnMarkerCondition(effect, pool, source);
            }
            return value;
        });
        if (info.tag() != null && actual.is(Blocks.COMMAND_BLOCK)) {
            ctx.addPostTodo(pos, world -> {
                ((ServerChunkCache) world.getLevel().getChunkSource()).blockChanged(pos);
                world.scheduleTick(pos, actual.getBlock(), 1);
            });
        }
    }

    @FunctionalInterface
    interface ConditionResolver {
        @Nullable Identifier select(String pool, RandomSource random, String effect);
    }

    /** The same final handler with condition lookup supplied by the generating environment. */
    static void apply(ChunkAccess chunk, BlockPos pos, BlockState actual, CompiledPalette.Placed material,
                      Preset preset, long seed, ConditionResolver conditions) {
        Palette.Info info = material.info();
        if (info == null) {
            return;
        }
        CompoundTag generated = null;
        if (info.mobId() != null && !info.mobId().isEmpty() && MarkerCapabilities.supportsSpawner(actual)) {
            Identifier mob = conditions.select(info.mobId(),
                    Rng.atPos(seed, pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.SPAWNERS), "spawner");
            if (mob != null) {
                generated = spawnerNbt(mob);
            }
        }
        if (info.loot() != null && !info.loot().isEmpty() && MarkerCapabilities.supportsLoot(actual)
                && SpecialMarkerPolicy.populateLoot(seed, pos, preset)) {
            Identifier loot = conditions.select(info.loot(),
                    Rng.atPos(seed, pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.LOOT), "loot");
            if (loot != null) {
                generated = lootNbt(loot);
            }
        }
        applyNbt(chunk, pos, actual, info.tag(), generated);
    }

    /**
     * Commits this final material's data without reading an earlier marker's queued NBT. Authored
     * fields override generated fields; the actual block entity type and destination are reserved.
     * This never changes a block, so applying loot cannot restore a block removed by damage.
     */
    public static void applyNbt(ChunkAccess chunk, BlockPos pos, BlockState actual,
                                @Nullable CompoundTag authored, @Nullable CompoundTag generated) {
        if (authored == null && generated == null) {
            return;
        }
        BlockEntityType<?> type = MarkerCapabilities.blockEntityType(actual);
        if (type != null) {
            queueBlockEntityNbt(chunk, pos, type, authored, generated);
        }
    }

    static void queueBlockEntityNbt(ChunkAccess chunk, BlockPos pos, BlockEntityType<?> type,
                                     @Nullable CompoundTag authored, @Nullable CompoundTag generated) {
        CompoundTag tag = generated == null ? new CompoundTag() : generated.copy();
        if (authored != null) {
            tag.merge(authored);
        }
        tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        chunk.setBlockEntityNbt(tag);
    }

    static CompoundTag spawnerNbt(Identifier mob) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", mob.toString());
        SpawnData data = new SpawnData(entity, Optional.empty(), Optional.empty());
        CompoundTag tag = new CompoundTag();
        tag.put("SpawnData", SpawnData.CODEC.encodeStart(NbtOps.INSTANCE, data).result()
                .orElseThrow(() -> new IllegalStateException("Invalid SpawnData")));
        return tag;
    }

    static CompoundTag lootNbt(Identifier loot) {
        CompoundTag tag = new CompoundTag();
        tag.putString("LootTable", loot.toString());
        // The previous setLootTable call left a new container's default seed at zero. Do not draw
        // another random number here: the position stream is used only for condition selection.
        tag.putLong("LootTableSeed", 0L);
        return tag;
    }

    @Nullable
    static Identifier selectCondition(LevelAccessor world, PlanningContext provider, ChunkPlan owner,
                                       ChunkPlan.ConditionTodo todo, BlockPos pos, RandomSource random,
                                       String effect) {
        int level = (pos.getY() - provider.baseGroundLevel()) / CityGenerator.FLOORHEIGHT;
        int floor = (pos.getY() - owner.getCityGroundLevel()) / CityGenerator.FLOORHEIGHT;
        ConditionContext context = new ConditionContext(level, floor, owner.cellars, owner.getNumFloors(),
                todo.getPart(), ConditionContext.NO_PART, todo.getBuilding(), owner.coord) {
            @Override
            public Identifier getBiome() {
                return world.getBiome(pos).unwrap().map(ResourceKey::identifier,
                        biome -> world.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome));
            }
        };
        Condition condition = provider.assets().conditions().getOrThrow(todo.getCondition());
        return selectCondition(condition, context, random, effect);
    }

    @Nullable
    static Identifier selectCondition(Condition condition, ConditionContext context,
                                       RandomSource random, String effect) {
        String value = condition.getRandomValue(random, context);
        return value == null ? null : Identifier.tryParse(value);
    }
}
