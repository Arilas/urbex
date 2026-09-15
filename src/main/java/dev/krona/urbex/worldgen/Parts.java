package dev.krona.urbex.worldgen;

import dev.krona.urbex.editor.EditModeData;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.DensitySelector;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.IBuildingPart;
import dev.krona.urbex.worldgen.lost.cityassets.LightSource;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import dev.krona.urbex.worldgen.lost.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Writes a building part's blocks, retaining its selected materials and placement origin.
 *
 * <p>Light sockets and world-only tasks are queued here. Block-entity decorators run through
 * {@link MarkerDecorations} once the final surviving material is known.</p>
 *
 * <p>In {@code worldgen} rather than {@code worldgen.gen}, unlike the other passes split out of the
 * generator. This one queues deferred work through {@code ChunkGenContext.addPostTodo} and the
 * driver's accepted socket writes, which are package-private on purpose - issue #127 moved runtime callbacks
 * off the planning types precisely so that queueing them stayed inside this package. Making them
 * public to let this class sit next to its siblings would undo that.</p>
 */
public class Parts {

    private Parts() {
    }

    public static int generatePart(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, IBuildingPart part,
                             Transform transform,
                             int ox, int oy, int oz, CityGenerator.HardAirSetting airWaterLevel) {
        ChunkDriver driver = ctx.driver;
        PlacementOrigin origin = new PlacementOrigin(info, part.getName());
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
                        // One lookup for the state and what the marker carries, which LOAD.022 makes an
                        // INVARIANT: "Resolving a marker to a state and to its traits is one lookup, not
                        // two". It used to be paletteAt followed by getInfo, two lookups into two maps,
                        // and a version 2 marker cannot answer the second at all - its traits are per
                        // slot (LOAD.021), so there is no per-marker Info to fetch. One call path here,
                        // whichever format the palette that defined this marker was written in.
                        CompiledPalette.Placed placed = ctx.placedAt(compiledPalette, c, rx, oy + y, rz);
                        if (placed == null) {
                            throw new RuntimeException("Could not find entry '" + c + "' in the palette for part '" + part.getName() + "'!");
                        }
                        CompiledPalette.Placed original = placed;
                        placed = placed.selectOptional(ctx.profile, ctx.seed, driver.getX(), driver.getY(), driver.getZ());
                        BlockState b = transformMarker(placed, transform);
                        Palette.Info inf = placed.info();

                        // Ordinary authored air is transparent; socket placeholders are writes,
                        // even when a socket's representative candidate happens to be air.
                        boolean socket = inf != null && inf.lightSource() != null && inf.lightSource().isSocket();
                        if (b != feature.air || placed != original || socket) {
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
                                // Socket geometry is deferred; NBT, loot and mobs belong to the
                                // material that survives all writes and run during finalization.
                                // Metadata-bearing blocks retain their real state: a POI dirt
                                // placeholder would hide the block entity from that finalizer.
                                if (inf.lightSource() != null) {
                                    b = handleLightSource(ctx, feature, inf.lightSource(), b,
                                            driver.getCurrentCopy());
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
                            } else if (ctx.tags.needsTodo(b)) {
                                b = handleTodo(ctx, feature, info, oy, ctx.region, rx, rz, y, b);
                            }
                            // Queue generation notifications for the actual placement, including
                            // metadata-bearing emitters. The handler preserves final NBT; vanilla
                            // initializes lighting after this generation stage.
                            if (b.getLightEmission() > 0) {
                                CityGenerator.updateNeeded(ctx, driver.getCurrentCopy(), Block.UPDATE_CLIENTS);
                            }
                            if (socket) {
                                writeLightMarker(ctx, b, placed.transformed(transform), origin);
                                driver.incY();
                            } else {
                                writeMarker(driver, placed, b, ctx.seed, transform, origin);
                            }
                        } else {
                            driver.incY();
                        }
                    }
                }
            }
        }
        return oy + part.getSliceCount();
    }

    /** Final part-placement seam: keep the selected slot's damage form with the accepted write. */
    static void writeMarker(ChunkDriver driver, CompiledPalette.Placed placed, BlockState state, long seed) {
        writeMarker(driver, placed, state, seed, Transform.ROTATE_NONE);
    }

    static void writeMarker(ChunkDriver driver, CompiledPalette.Placed placed, BlockState state, long seed,
                            Transform transform) {
        writeMarker(driver, placed, state, seed, transform, null);
    }

    static void writeMarker(ChunkDriver driver, CompiledPalette.Placed placed, BlockState state, long seed,
                            Transform transform, PlacementOrigin origin) {
        CompiledPalette.Placed material = placed.transformed(transform);
        if (origin == null) {
            driver.add(state, material);
        } else {
            driver.add(state, material, origin);
        }
    }

    /** Mirror first, then rotate, using this selected slot's compiled trait rather than a block tag. */
    static BlockState transformMarker(CompiledPalette.Placed placed, Transform transform) {
        return placed.transformed(transform).state();
    }

    /**
     * What a {@code lightSource} marker writes: the light, or the replacement it named.
     * <p>
     * Nothing is filtered out of the output here, which is the whole of the change. A rejected
     * marker used to become air whatever the author had written at it, so the only lights lighting
     * density could reach were the ones authored as light markers in the first place - and a pack
     * that authored its lanterns as ordinary blocks, as ModernTweaks does for all 826 of its
     * lantern positions, had a setting that did nothing at all. Now the entry says it is a light,
     * says what stands there when it is off, and the density decides between the two.
     *
     * @param lit the state the palette already resolved for this character, used by an in-place
     *            source. A socket ignores it: its pool is its block source.
     */
    public static BlockState handleLightSource(ChunkGenContext ctx, CityGenerator feature,
                                               LightSource source, BlockState lit, BlockPos pos) {
        if (source.isSocket()) {
            // Selection is pure. Admission is coupled to the accepted placeholder write below,
            // so clipped or overwritten markers cannot leave work in the deferred queue.
            return feature.air;
        }
        boolean on = DensitySelector.lighting(ctx.seed, pos, ctx.info.profile.lightingDensity());
        return on ? lit : source.unlitAt(ctx.seed, pos);
    }

    /** Writes at the current cursor without advancing, admitting only accepted socket placeholders. */
    public static void writeLightMarker(ChunkGenContext ctx, BlockState actual,
                                        CompiledPalette.Placed source, PlacementOrigin origin) {
        Palette.Info info = source == null ? null : source.info();
        if (info != null && info.lightSource() != null && info.lightSource().isSocket()) {
            boolean lit = DensitySelector.lighting(ctx.seed, ctx.driver.getCurrentCopy(),
                    ctx.info.profile.lightingDensity());
            ctx.driver.blockLightSocket(source, lit, origin);
        } else {
            ctx.driver.block(actual, source, origin);
        }
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

    private static void queueBlockEntityNbt(ChunkAccess chunk, BlockPos pos, BlockEntityType<?> type,
                                            CompoundTag authored, CompoundTag spawnerNbt) {
        String typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString();
        CompoundTag compatible = spawnerNbt != null && typeId.equals(spawnerNbt.getStringOr("id", ""))
                ? spawnerNbt : null;
        MarkerDecorations.queueBlockEntityNbt(chunk, pos, type, authored, compatible);
    }

    /**
     * Drop queued NBT whose type no longer fits the block after post-todos have run.
     *
     * <p>The driver invalidates earlier block entities at overwritten positions and applies the
     * surviving marker's decorators during finalization. Post-todos run afterwards and can still
     * replace blocks directly in the world. This last compatibility check removes any resulting
     * stale queue entries before Minecraft saves or promotes the chunk.</p>
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

    public static Identifier getRandomSpawnerMob(Level world, RandomSource random, PlanningContext diminfo, ChunkPlan info, ChunkPlan.ConditionTodo todo, BlockPos pos) {
        return MarkerDecorations.selectCondition(world, diminfo, info, todo, pos, random, "spawner");
    }

    /** Compatibility helper for callers decorating an already-live container. */
    public static void createLoot(ChunkPlan info, RandomSource random, LevelAccessor world, BlockPos pos,
                                  ChunkPlan.ConditionTodo todo, PlanningContext diminfo) {
        if (todo != null && world.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container) {
            Identifier loot = MarkerDecorations.selectCondition(world, diminfo, info, todo, pos, random, "loot");
            if (loot != null) {
                container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, loot));
            }
        }
    }

    public static void setBlocksFromPalette(ChunkGenContext ctx, CityGenerator feature, int x, int y, int z, int y2, CompiledPalette palette, char character) {
        ChunkDriver driver = ctx.driver;
        if (palette.isSimple(character)) {
            CompiledPalette.Placed b = ctx.selectedAt(palette, character, x, y, z);
            driver.setBlockRange(x, y, z, y2, b);
        } else {
            driver.current(x, y, z);
            while (y < y2) {
                driver.add(ctx.selectedHere(palette, character));
                y++;
            }
        }
    }
}
