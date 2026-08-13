package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.editor.EditModeData;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.ModSetup;
import dev.krona.urbex.varia.*;
import dev.krona.urbex.worldgen.gen.*;
import dev.krona.urbex.worldgen.lost.*;
import dev.krona.urbex.worldgen.lost.cityassets.*;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import java.util.*;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Writing a building part's blocks into the chunk, and everything they carry with them.
 *
 * <p>The block-layout loop itself, plus the markers a palette entry can attach to a block: block
 * entities and their NBT, spawners, loot tables, and the deferred todos that need a real world
 * rather than a half-built chunk. Moved out of {@link CityGenerator} unchanged - same code, same
 * order, same RNG draws (issue #11).</p>
 *
 * <p>In {@code worldgen} rather than {@code worldgen.gen}, unlike the other passes split out of the
 * generator. This one queues deferred work through {@code ChunkGenContext.addPostTodo} and
 * {@code addLightTodo}, which are package-private on purpose - issue #127 moved runtime callbacks
 * off the planning types precisely so that queueing them stayed inside this package. Making them
 * public to let this class sit next to its siblings would undo that.</p>
 */
public class Parts {

    private Parts() {
    }

    /**
     * Which block entity type belongs to a block. Bounded by the block registry, so it needs no
     * eviction policy; {@link Optional#empty()} stands for "asked, and there is none" so a miss is
     * remembered as well as a hit (issue #132).
     */
    private static final Map<Block, Optional<BlockEntityType>> TYPE_CACHE = new ConcurrentHashMap<>();

    public static int generatePart(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, IBuildingPart part,
                             Transform transform,
                             int ox, int oy, int oz, CityGenerator.HardAirSetting airWaterLevel) {
        ChunkDriver driver = ctx.driver;
        if (feature.profile.editMode()) {
            EditModeData.getData().addPartData(info.coord, oy, part.getName());
        }
        CompiledPalette compiledPalette = computePalette(feature, info, part);

        boolean nowater = part.getMetaBoolean(BuildingPart.META_NOWATER);

        for (int x = 0; x < part.getXSize(); x++) {
            for (int z = 0; z < part.getZSize(); z++) {
                char[] vs = part.getVSlice(x, z);
                if (vs != null) {
                    int rx = ox + transform.rotateX(x, z);
                    int rz = oz + transform.rotateZ(x, z);
                    driver.current(rx, oy, rz);
                    int len = vs.length;
                    for (int y = 0; y < len; y++) {
                        char c = vs[y];
                        BlockState b = ctx.paletteAt(compiledPalette, c, rx, oy + y, rz);
                        if (b == null) {
                            throw new RuntimeException("Could not find entry '" + c + "' in the palette for part '" + part.getName() + "'!");
                        }

                        Palette.Info inf = compiledPalette.getInfo(c);

                        if (transform != Transform.ROTATE_NONE) {
                            b = transformBlockState(feature, ctx.tags, info, transform, b);
                        }

                        // We don't replace the world where the part is empty (feature.air)
                        if (b != feature.air) {
                            if (b == feature.liquid) {
                                if (info.profile.avoidWater()) {
                                    b = feature.air;
                                }
                            } else if (b == feature.hardAir) {
                                switch (airWaterLevel) {
                                    case AIR:
                                        b = feature.air;
                                        break;
                                    case WATERLEVEL:
                                        if (!info.profile.avoidFoliage() && !nowater && oy + y < info.waterLevel) {
                                            b = feature.liquid;
                                        } else {
                                            b = feature.air;
                                        }
                                        break;
                                    case VOID:
                                        // feature.hardAir (STRUCTURE_VOID) is replaced by whatever was already there
                                        break;
                                }
                            } else if (inf != null) {
                                if (inf.light() != null || inf.isTorch()) {
                                    b = handleLightMarker(ctx, feature, inf, driver.getCurrentCopy());
                                } else if (inf.loot() != null && !inf.loot().isEmpty()) {
                                    handleLoot(ctx, feature, info, part, b, inf);
                                } else if (inf.mobId() != null && !inf.mobId().isEmpty()) {
                                    // ctx.region, not feature.provider.getWorld(): these write block entity
                                    // NBT into a chunk, which only the generating region has.
                                    b = handleSpawner(ctx, feature, info, part, oy, ctx.region, rx, rz, y, b, inf);
                                } else if (inf.tag() != null) {
                                    b = handleBlockEntity(ctx, feature, info, oy, ctx.region, rx, rz, y, b, inf);
                                }
                            } else if (ctx.tags.needsPoiUpdate(b)) {
                                // If this block has POI data we need to delay setting it
                                BlockState finalB = b;
                                BlockPos p = driver.getCurrentCopy();
                                ctx.addPostTodo(p, inWorld -> {
                                    if (inWorld.getBlockState(p).getBlock() == Blocks.DIRT) {
                                        inWorld.setBlock(p, finalB, Block.UPDATE_NONE);
                                    }
                                });
                                b = Blocks.DIRT.defaultBlockState();
                            } else if (ctx.tags.needsLightingUpdate(b)) {
                                CityGenerator.updateNeeded(ctx, driver.getCurrentCopy(), Block.UPDATE_CLIENTS);
                            } else if (ctx.tags.needsTodo(b)) {
                                b = handleTodo(ctx, feature, info, oy, ctx.region, rx, rz, y, b);
                            }
                            driver.add(b);
                        } else {
                            driver.incY();
                        }
                    }
                }
            }
        }
        return oy + part.getSliceCount();
    }

    public static BlockState handleLightMarker(ChunkGenContext ctx, CityGenerator feature, Palette.Info marker, BlockPos pos) {
        if (DensitySelector.lighting(ctx.seed, pos, ctx.info.profile.lightingDensity())) {
            ctx.addLightTodo(pos, marker.light());
        }
        return feature.air;
    }

    /**
     * The chunk's palette with this part's local palette merged over it.
     * <p>
     * This carried an upstream {@code // Cache the combined palette?} comment and answered it by
     * building a fresh {@link CompiledPalette} - deep-copying three maps over a hundred-odd entries -
     * for every part with a local palette in every chunk. The answer is yes, and it is keyed on the
     * two compiled assets involved rather than on the chunk (issue #53).
     */
    public static CompiledPalette computePalette(CityGenerator feature, ChunkPlan info, IBuildingPart part) {
        return feature.provider.caches().palettes.with(info.getCompiledPalette(), part.getLocalPalette());
    }

    private static BlockEntityType getTypeForBlock(CityGenerator feature, BlockState state) {
        // get / compute-outside / putIfAbsent, not computeIfAbsent: the registry walk used to
        // run inside a ConcurrentHashMap bin lock, stalling every other worldgen thread whose
        // block hashed into the same bin (issue #25). Racing threads compute the same answer.
        Block block = state.getBlock();
        Optional<BlockEntityType> existing = TYPE_CACHE.get(block);
        if (existing != null) {
            return existing.orElse(null);
        }
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (type.isValid(state)) {
                Optional<BlockEntityType> raced = TYPE_CACHE.putIfAbsent(block, Optional.of(type));
                return raced != null ? raced.orElse(null) : type;
            }
        }
        // Remember the miss too. A palette entry carrying NBT for a block that is not a block
        // entity is a datapack error, and the caller warns about it - but without this the registry
        // walk ran again for every block placed from that entry, on a worldgen worker, for as long
        // as the world was played. Optional rather than a sentinel type, because every real
        // BlockEntityType is a value this map legitimately holds (issue #132).
        TYPE_CACHE.putIfAbsent(block, Optional.empty());
        return null;
    }

    private static BlockState handleBlockEntity(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int oy, WorldGenLevel world, int rx, int rz, int y, BlockState b, Palette.Info inf) {
        BlockPos pos = info.getRelativePos(rx, oy + y, rz);
        BlockEntityType type = getTypeForBlock(feature, b);
        if (type == null) {
            ModSetup.getLogger().warn("Error getting type for block: " + b.getBlock());
            return b;
        }
        CompoundTag tag = inf.tag().copy();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
        world.getChunk(pos).setBlockEntityNbt(tag);
        if (b.getBlock() == Blocks.COMMAND_BLOCK) {
            ctx.addPostTodo(pos, inWorld -> {
                ((ServerChunkCache) inWorld.getLevel().getChunkSource()).blockChanged(pos);
                inWorld.scheduleTick(pos, b.getBlock(), 1);
            });
        }
        return b;
    }

    /**
     * Forget queued block entity data for blocks that a later pass has overwritten.
     *
     * A spawner or a tagged block entity queues its NBT with setBlockEntityNbt the
     * moment the part owning it is generated, but everything that runs afterwards —
     * ruins above all, plus explosions, rubble, stuff and the post-todos — writes
     * through the ChunkDriver or through setBlock, and neither of those touches that
     * queue. ProtoChunk.setBlockState does not either; clearing it is ours to do.
     *
     * What is left is a spawner queued onto the feature.air that replaced it. Minecraft
     * discovers this when the chunk is saved or promoted, logs "Invalid block entity"
     * with a full stack trace, and throws the data away anyway — so dropping it here
     * changes nothing about the world and removes the noise from the log.
     */
    public static void forgetBlockEntities(ChunkAccess chunk) {
        // getBlockEntitiesPos() hands back a copy, so removing while iterating is safe.
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            CompoundTag tag = chunk.getBlockEntityNbt(pos);
            if (tag == null) {
                continue;   // a real block entity, already validated against its block
            }
            Identifier id = Identifier.tryParse(tag.getStringOr("id", ""));
            BlockEntityType<?> type = id == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id);
            if (type == null || !type.isValid(chunk.getBlockState(pos))) {
                chunk.removeBlockEntity(pos);
            }
        }
    }

    private static BlockState handleSpawner(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, IBuildingPart part, int oy, WorldGenLevel world, int rx, int rz, int y, BlockState b, Palette.Info inf) {
        if (SpecialMarkerPolicy.generateSpawner(info.profile)) {
            String mobid = inf.mobId();
            BlockPos pos = info.getRelativePos(rx, oy + y, rz);
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putString("id", "minecraft:mob_spawner");
            // Keyed on the spawner's own position: which mob a spawner gets must not depend on
            // how many spawners this chunk happened to place before it.
            RandomSource spawnerRandom = Rng.atPos(feature.provider.seed(), pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.SPAWNERS);
            Identifier randomValue = getRandomSpawnerMob(world.getLevel(), spawnerRandom, feature.provider, info,
                    new ChunkPlan.ConditionTodo(mobid, part.getName(), info), pos);
            CompoundTag sd = new CompoundTag();
            sd.putString("id", randomValue.toString());
            SpawnData data = new SpawnData(sd, Optional.empty(), Optional.empty());
            tag.put("SpawnData", SpawnData.CODEC.encodeStart(NbtOps.INSTANCE, data).result().orElseThrow(() -> new IllegalStateException("Invalid SpawnData")));

            world.getChunk(pos).setBlockEntityNbt(tag);
        } else {
            b = feature.air;
        }
        return b;
    }

    private static void handleLoot(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, IBuildingPart part,
                            BlockState block, Palette.Info marker) {
        BlockPos pos = ctx.driver.getCurrentCopy();
        if (!SpecialMarkerPolicy.populateLoot(feature.provider.seed(), pos, info.profile)) {
            return;
        }
        ctx.addPostTodo(pos, inWorld -> {
            if (!inWorld.getBlockState(pos).isAir()) {
                inWorld.setBlock(pos, block, Block.UPDATE_CLIENTS);
                generateLoot(feature, info, inWorld, pos,
                        new ChunkPlan.ConditionTodo(marker.loot(), part.getName(), info));
            }
        });
    }

    private static BlockState handleTodo(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int oy, WorldGenLevel world, int rx, int rz, int y, BlockState b) {
        Block block = b.getBlock();
        CityStyle cs = info.getCityStyle();
        boolean avoidFoliage = info.profile.avoidFoliage();
        if (cs.getAvoidFoliage() != null) {
            avoidFoliage = cs.getAvoidFoliage();
        }
        if (block instanceof SaplingBlock || block instanceof FlowerBlock) {
            if (avoidFoliage) {
                b = feature.air;
            } else {
                BlockPos pos = info.getRelativePos(rx, oy + y, rz);
                if (block instanceof SaplingBlock saplingBlock) {
                    BlockState finalB = b;
                    if (Config.forceSaplingGrowth()) {
                        // The todo runs later, on the server thread, long after this context is gone.
                        // Key the tree it grows on the sapling's position so it is the same tree no
                        // matter when the todo is drained.
                        RandomSource growthRandom = Rng.atPos(feature.provider.seed(), pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.VEGETATION_GROWTH);
                        ctx.addLevelTask(pos, level -> {
                            // Not available yet is not the same as nothing to do. This used to
                            // return either way and the queue counted it done, so a tree whose
                            // chunk happened to be unloaded when the drain reached it simply never
                            // grew (issue #127).
                            if (!level.hasChunksAt(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                                return LevelTaskQueue.Outcome.RETRY;
                            }
                            if (level.getBlockState(pos).getBlock() instanceof SaplingBlock) {
                                level.setBlock(pos, finalB, Block.UPDATE_CLIENTS);
                                saplingBlock.advanceTree(level, pos, finalB, growthRandom);
                            }
                            // Either it grew, or something else stands there now and no sapling is
                            // coming back to that position. Retrying would never end.
                            return LevelTaskQueue.Outcome.DONE;
                        });
                    } else {
                        ctx.addPostTodo(pos, inWorld -> {
                            BlockState state = finalB.setValue(SaplingBlock.STAGE, 1);
                            inWorld.setBlock(pos, state, Block.UPDATE_ALL_IMMEDIATE);
                        });
                    }
                }
            }
        }
        return b;
    }

    /**
     * Applies a part's transform to one block state, using the {@code rotatable} tag of the world
     * style governing this chunk.
     * <p>
     * The tag used to be resolved once and cached on the generator, because the world style could
     * not change under a running generator. It can now: two cities in one world can come from
     * different packs whose {@code rotatable} tags differ, so the tag has to follow the chunk.
     * {@link ChunkPlan#worldStyle()} memoises it per chunk, so this stays a field read in the hot
     * path rather than a neighbourhood walk. A world style that declares no {@code rotatable}
     * resolves {@code urbex:rotatable}, as before.
     * <p>
     * Membership is answered by the chunk's own {@link TagSnapshot} rather than by a live registry
     * read, so every block of every part in this chunk sees one tag epoch even if a {@code /reload}
     * lands halfway through it (issue #128).
     */
    private static BlockState transformBlockState(CityGenerator feature, TagSnapshot tags, ChunkPlan info, Transform transform, BlockState b) {
        if (tags.isRotatable(info.worldStyle().getRotatableTag(), b)) {
            // Vanilla structure order: mirror first, then rotate. The mirror used to be
            // approximated with a 180/90 rotation, which turned mirrored stairs/doors/logs
            // the wrong way (issue #45).
            b = b.mirror(transform.getMcMirror()).rotate(transform.getMcRotation());
        } else if (feature.getRailStates().contains(b)) {
            EnumProperty<RailShape> shapeProperty;
            if (b.getBlock() == Blocks.RAIL) {
                shapeProperty = RailBlock.SHAPE;
            } else if (b.getBlock() == Blocks.POWERED_RAIL) {
                shapeProperty = PoweredRailBlock.SHAPE;
            } else {
                throw new RuntimeException("Error with rail!");
            }
            RailShape shape = b.getValue(shapeProperty);
            b = b.setValue(shapeProperty, transform.transform(shape));
        }
        return b;
    }


    public static Identifier getRandomSpawnerMob(Level world, RandomSource random, PlanningContext diminfo, ChunkPlan info, ChunkPlan.ConditionTodo todo, BlockPos pos) {
        String condition = todo.getCondition();
        Condition cnd = diminfo.assets().conditions().getOrThrow(condition);
        int level = (pos.getY() - diminfo.preset().groundLevel()) / CityGenerator.FLOORHEIGHT;
        int floor = (pos.getY() - info.getCityGroundLevel()) / CityGenerator.FLOORHEIGHT;
        String belowFloor = ConditionContext.NO_PART;
        ConditionContext conditionContext = new ConditionContext(level, floor, info.cellars, info.getNumFloors(),
                todo.getPart(), belowFloor, todo.getBuilding(), info.coord) {
            @Override
            public Identifier getBiome() {
                return world.getBiome(pos).unwrap().map(ResourceKey::identifier, biome -> world.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome));
            }
        };
        String randomValue = cnd.getRandomValue(random, conditionContext);
        if (randomValue == null) {
            throw new RuntimeException("Condition '" + cnd.getName() + "' did not return a valid mob!");
        }
        return Identifier.parse(randomValue);
    }


    private static void generateLoot(CityGenerator feature, ChunkPlan info, LevelAccessor world, BlockPos pos, ChunkPlan.ConditionTodo condition) {
        BlockEntity te = world.getBlockEntity(pos);
        if (te instanceof RandomizableContainerBlockEntity) {
            // Runs from a post-todo, after generation of this chunk has finished, so it cannot
            // borrow the context's streams. The chest's own position addresses it instead.
            RandomSource lootRandom = Rng.atPos(feature.provider.seed(), pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.LOOT);
            createLoot(info, lootRandom, world, pos, condition, feature.provider);
        } else if (te == null) {
            ModSetup.getLogger().error("Error setting loot at {},{},{}", pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public static void createLoot(ChunkPlan info, RandomSource random, LevelAccessor world, BlockPos pos, ChunkPlan.ConditionTodo todo, PlanningContext diminfo) {
        BlockEntity tileentity = world.getBlockEntity(pos);
        if (tileentity instanceof RandomizableContainerBlockEntity rcbe) {
            if (todo != null) {
                String lootTable = todo.getCondition();
                int level = (pos.getY() - diminfo.preset().groundLevel()) / CityGenerator.FLOORHEIGHT;
                int floor = (pos.getY() - info.getCityGroundLevel()) / CityGenerator.FLOORHEIGHT;
                ConditionContext conditionContext = new ConditionContext(level, floor, info.cellars, info.getNumFloors(),
                        todo.getPart(), ConditionContext.NO_PART, todo.getBuilding(), info.coord) {
                    @Override
                    public Identifier getBiome() {
                        return world.getBiome(pos).unwrap().map(ResourceKey::identifier, biome -> world.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome));
                    }
                };
                String randomValue = diminfo.assets().conditions().getOrThrow(lootTable).getRandomValue(random, conditionContext);
//                ((LockableLootTileEntity) tileentity).setLootTable(Identifier.fromNamespaceAndPath(randomValue), random.nextLong());
//                tileentity.markDirty();
//                    Urbex.setup.getLogger().debug("createLootChest: loot=" + randomValue + " pos=" + pos.toString());
//                }
                rcbe.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(randomValue)));
            }
        }
    }


    public static void setBlocksFromPalette(ChunkGenContext ctx, CityGenerator feature, int x, int y, int z, int y2, CompiledPalette palette, char character) {
        ChunkDriver driver = ctx.driver;
        if (palette.isSimple(character)) {
            BlockState b = ctx.paletteAt(palette, character, x, y, z);
            driver.setBlockRange(x, y, z, y2, b);
        } else {
            driver.current(x, y, z);
            while (y < y2) {
                driver.add(ctx.paletteHere(palette, character));
                y++;
            }
        }
    }
}
