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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CityGenerator {

    public static final int FLOORHEIGHT = 6;

    public final BlockState air;
    private final BlockState hardAir;

    private final BlockState base;
    public final BlockState liquid;

    // Built on first use and cached on this object, which is shared by the whole dimension. That was
    // safe only because generation held a lock on this feature. It is a pure function of two vanilla
    // blocks - no Level, no tag, no datapack - so it is built once in the constructor and never
    // written again. The three fields that used to sit beside it were block-tag expansions and have
    // moved to TagSnapshot, so a /reload can refresh them without rebuilding this generator and
    // everything the level plans with (issue #128); this one has nothing to reload.
    private final Set<BlockState> railStates;

    private final NoiseGeneratorPerlin rubbleNoise;
    private final NoiseGeneratorPerlin leavesNoise;
    private final NoiseGeneratorPerlin ruinNoise;
    private final NoiseGeneratorPerlin bottomLayerNoise;    // Used in floating profile for the underside of buildings

    private final BlockState[] randomLeafs;
    private final BlockState[] randomDirt;
    private final Set<BlockState> randomDirtSet;

    public final IDimensionInfo provider;
    public final Preset profile;

    private final Statistics statistics = new Statistics();
    private final Map<Block, BlockEntityType> typeCache = new ConcurrentHashMap<>();

    public CityGenerator(IDimensionInfo provider, Preset profile) {
        this.provider = provider;
        this.profile = profile;
//        int waterLevel = provider.getWorld() == null ? 65 : Tools.getSeaLevel(provider.getWorld());// profile.GROUNDLEVEL - profile.WATERLEVEL_OFFSET;
        // Four independent noise fields, each seeded from the world seed alone. They describe the
        // whole dimension rather than one chunk, so they take a fixed coordinate and are built once.
        long seed = provider.getSeed();
        this.rubbleNoise = new NoiseGeneratorPerlin(Rng.at(seed, 0, 0, Rng.Purpose.NOISE), 4);
        this.leavesNoise = new NoiseGeneratorPerlin(Rng.at(seed, 1, 0, Rng.Purpose.NOISE), 4);
        this.ruinNoise = new NoiseGeneratorPerlin(Rng.at(seed, 2, 0, Rng.Purpose.NOISE), 4);
        this.bottomLayerNoise = new NoiseGeneratorPerlin(Rng.at(seed, 3, 0, Rng.Purpose.NOISE), 4);

        air = Blocks.AIR.defaultBlockState();
        hardAir = Blocks.STRUCTURE_VOID.defaultBlockState();
        base = profile.getBaseBlock();
        liquid = profile.getLiquidBlock();

        railStates = new HashSet<>();
        addStates(Blocks.RAIL, railStates);
        addStates(Blocks.POWERED_RAIL, railStates);

        randomLeafs = buildRandomLeafs();
        randomDirt = buildRandomDirt();
        randomDirtSet = Set.of(Blocks.MOSSY_STONE_BRICKS.defaultBlockState(),
                Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
                Blocks.MOSS_BLOCK.defaultBlockState());

//        islandTerrainGenerator.setup(provider.getWorld().getWorld(), provider);
//        cavernTerrainGenerator.setup(provider.getWorld().getWorld(), provider);
//        spaceTerrainGenerator.setup(provider.getWorld().getWorld(), provider);
    }

    private static BlockState[] buildRandomLeafs() {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockState leaves2 = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockState leaves3 = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

        BlockState[] result = new BlockState[128];
        int i = 0;
        while (i < 20) {
            result[i] = leaves2;
            i++;
        }
        while (i < 40) {
            result[i] = leaves3;
            i++;
        }
        while (i < result.length) {
            result[i] = leaves;
            i++;
        }
        return result;
    }

    private static BlockState[] buildRandomDirt() {
        BlockState mBricks = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        BlockState mCobble = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        BlockState moss = Blocks.MOSS_BLOCK.defaultBlockState();

        BlockState[] result = new BlockState[128];
        int i = 0;
        while (i < 20) {
            result[i] = mBricks;
            i++;
        }
        while (i < 60) {
            result[i] = mCobble;
            i++;
        }
        while (i < result.length) {
            result[i] = moss;
            i++;
        }
        return result;
    }

    /**
     * One leaf state for the block the driver is about to write. Addressed by that position, so
     * how many leaves this chunk placed first cannot change which one this is.
     */
    private BlockState getRandomLeaf(ChunkGenContext ctx, BuildingInfo info, CompiledPalette compiledPalette) {
        Character leavesBlock = info.getCityStyle().getLeavesBlock();
        if (leavesBlock != null) {
            return ctx.paletteHere(compiledPalette, leavesBlock);
        }
        return randomLeafs[Rng.indexAtPos(ctx.seed, ctx.driver.getX(), ctx.driver.getY(), ctx.driver.getZ(),
                Rng.Purpose.LEAVES, randomLeafs.length)];
    }

    private Set<BlockState> getPossibleRandomDirts(ChunkGenContext ctx, BuildingInfo info, CompiledPalette compiledPalette) {
        Character rubbleDirtBlock = info.getCityStyle().getRubbleDirtBlock();
        if (rubbleDirtBlock != null) {
            return compiledPalette.getAll(rubbleDirtBlock);
        } else {
            return randomDirtSet;
        }
    }

    /**
     * One rubble state for the block the driver is about to write. Addressed by that position, for
     * the same reason as {@link #getRandomLeaf}. Nothing is regenerated here: {@code randomDirt} is
     * a final array built once in the constructor and never empty.
     */
    private BlockState getRandomDirt(ChunkGenContext ctx, BuildingInfo info, CompiledPalette compiledPalette) {
        Character rubbleDirtBlock = info.getCityStyle().getRubbleDirtBlock();
        if (rubbleDirtBlock != null) {
            return ctx.paletteHere(compiledPalette, rubbleDirtBlock);
        }
        return randomDirt[Rng.indexAtPos(ctx.seed, ctx.driver.getX(), ctx.driver.getY(), ctx.driver.getZ(),
                Rng.Purpose.RUBBLE, randomDirt.length)];
    }

    public Set<BlockState> getRailStates() {
        return railStates;
    }

    private static void addStates(Block block, Set<BlockState> set) {
        set.addAll(block.getStateDefinition().getPossibleStates());
    }

    private boolean isVoid(ChunkGenContext ctx, int x, int z) {
        ChunkDriver driver = ctx.driver;
        driver.current(x, provider.getWorld().getMaxY(), z);
        int minHeight = provider.getWorld().getMinY();
        while (driver.getBlock() == air && driver.getY() > minHeight) {
            driver.decY();
        }
        return driver.getY() == minHeight;
    }

    /**
     * @param runtime the level's runtime, captured by the caller for this one chunk. Everything it
     *                carries that outlives the chunk - today, the deferred-task queue - reaches
     *                generation through it, so a reload or an unload landing mid-chunk cannot
     *                redirect this generation's work to a different epoch (issue #125).
     */
    public void generate(DimensionRuntime runtime, WorldGenRegion region, ChunkAccess chunk) {
        long start = System.currentTimeMillis();

        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();

        ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);

        // A copy, because correctTerrainShape() below writes the corrected surface back into it.
        // The instance in caches().heightmap is shared with every other thread generating a chunk
        // near this one and must stay what getHeightmap() promises it is: a pure function of the
        // generator and the coordinate. Writing the correction into it instead would publish a
        // value whose presence depends on whether this chunk ran before or after its neighbour
        // read it - the neighbour's border height, and so the terrain, would depend on generation
        // order. See Arilas/urbex#24.
        ChunkHeightmap heightmap = new ChunkHeightmap(getHeightmap(coord, provider.getWorld()));
        BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
        // runtime.tags() is read here and nowhere else in this generation: one call, at the start,
        // so a /reload landing mid-chunk cannot be observed halfway through a building (issue #128).
        ChunkGenContext ctx = new ChunkGenContext(region, chunk, coord, provider, profile, info,
                runtime.tasks(), runtime.tags());

        boolean doCity = info.isCity;

        // Check if there is no village or other structure here. We don't do this for multibuildings because otherwise part of the multibuilding might be cut off
        AvoidChunk avoidChunk = AvoidChunk.NO;
        if (!info.multiBuildingPos.isMulti()) {
            avoidChunk = hasBlacklistedStructure(region, chunkX, chunkZ);
            if (avoidChunk != AvoidChunk.NO) {
                // Only this chunk's rendering. The cached BuildingInfo and ChunkCharacteristics used
                // to be rewritten here too - isCity flipped to false, from the thread generating this
                // chunk, on values every neighbour had already read and derived from. Everything they
                // had settled off isCity == true stayed settled; only chunks planned after the flip
                // saw it, so what a world looked like depended on generation order (issue #126).
                //
                // Suppression is local now, which is the same shape StructureSuppressor already uses
                // for the opposite policy: it cancels a structure inside a city without touching the
                // city's plan, and this cancels the city inside a structure without touching it
                // either. Neighbours keep planning as though the city were here, which is the honest
                // cost of the change - see the note on AvoidChunk.
                doCity = false;
            }
        }

        // If this chunk has a building or street but we're in a floating profile and
        // we happen to have a void chunk we detect that here and go back to normal chunk generation
        // anyway
        if (doCity && provider.getProfile().CITY_AVOID_VOID && provider.getProfile().isFloating()) {
            boolean v = isVoid(ctx, 2, 2) || isVoid(ctx, 2, 14) || isVoid(ctx, 14, 2) || isVoid(ctx, 14, 14) || isVoid(ctx, 8, 8);
            doCity = !v;
        }

        if (doCity) {
            doCityChunk(ctx, info, heightmap, chunk);
        } else {
            // We already have a prefilled core chunk (as generated from doCoreChunk)
            doNormalChunk(ctx, info, heightmap, avoidChunk);
        }

        Railway.RailChunkInfo railInfo = info.getRailInfo();
        if (railInfo.getType() != RailChunkType.NONE) {
            Railways.generateRailways(ctx, this, info, railInfo, heightmap);
        }
        Railways.generateRailwayDungeons(ctx, this, info);

        placeOptionalLights(ctx, info);

        if (info.getDamageArea().hasExplosions()) {
            breakBlocksForDamageNew(ctx, chunkX, chunkZ, info);
            fixAfterExplosion(ctx, info);
        }
        generateDebris(ctx, info);

        ctx.driver.actuallyGenerate(chunk);
        ChunkFixer.fix(ctx);
        // After the fixer, so the post-todos have placed their blocks and what we see is final
        forgetOverwrittenBlockEntities(chunk);

        long time = System.currentTimeMillis() - start;
        statistics.addTime(time);
    }

    public Statistics getStatistics() {
        return statistics;
    }

    private int getTopLevel(BuildingInfo info) {
        if (info.hasBuilding) {
            return info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT;
        } else {
            return info.getCityGroundLevel();
        }
    }

    /**
     * Whether structure avoidance suppresses this chunk's city.
     *
     * <p>{@code ADJACENT} is gone with the neighbourhood probe that produced it. It meant "a chunk
     * next to this one has an avoided structure", and answering that needed reading neighbouring
     * chunks - which the probe did through {@code level.hasChunk}, treating whatever the region did
     * not happen to hold as clear. That made the answer a property of generation order rather than of
     * the world (issue #126).</p>
     *
     * <p>It could not be made deterministic the way {@code StructureSuppressor} is. That one asks
     * planning a question from generation - {@code BuildingInfo.isCity}, pure seed and coordinate -
     * and the reverse, "is there a structure at that coordinate", has no seed-pure answer: vanilla
     * picks candidate chunks from the seed and then accepts or rejects each one against biome and
     * terrain at generation time. A mask built from candidates alone suppresses cities around
     * villages that never appear.</p>
     */
    public enum AvoidChunk {
        NO,
        YES
    }

    /**
     * Package-visible for {@link DigestRunner}, which counts how many sampled chunks this suppresses
     * so a digest window cannot silently stop covering avoidance. Called there after generation, with
     * every chunk loaded, so it answers without the neighbourhood gaps that make it order-dependent
     * on the generation path (issue #126).
     */
    static AvoidChunk hasBlacklistedStructure(WorldGenLevel level, int chunkX, int chunkZ) {
        if (!Config.AVOID_VILLAGES.get() && !Config.AVOID_SURFACE_STRUCTURES.get()
                && !Config.hasAvoidedStructures()) {
            return AvoidChunk.NO;
        }
        // This chunk only. It is the one being generated, so its own structure references are already
        // settled and reading them is a property of the chunk rather than of what else happens to be
        // loaded - which is the whole of what made the 3x3 probe this replaces order-dependent
        // (issue #126). The guard is kept for the one caller that asks about a chunk it does not hold:
        // DigestRunner's coverage counter, after generation.
        if (!level.hasChunk(chunkX, chunkZ)) {
            return AvoidChunk.NO;
        }
        ChunkAccess ch = level.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_REFERENCES);
        return testBlacklistedStructure(level, ch) ? AvoidChunk.YES : AvoidChunk.NO;
    }

    private static boolean testBlacklistedStructure(WorldGenLevel level, ChunkAccess ch) {
        if (ch.hasAnyStructureReferences()) {
            var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var references = ch.getAllReferences();
            for (var entry : references.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    Structure structure = entry.getKey();
                    Optional<ResourceKey<Structure>> key = structures.getResourceKey(structure);
                    if (Config.AVOID_VILLAGES.get()
                            && key.map(k -> structures.getOrThrow(k).is(StructureTags.VILLAGE)).orElse(false)) {
                        return true;
                    }
                    // Catch-all for structure mods: everything that builds at the surface step
                    if (Config.AVOID_SURFACE_STRUCTURES.get()
                            && structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) {
                        return true;
                    }
                    // An unregistered structure has no id to match against the blacklist
                    if (key.isPresent() && Config.isAvoidedStructure(key.get().identifier())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /** Finalize every optional-light marker admitted by this chunk-generation context. */
    public void placeOptionalLights(ChunkGenContext ctx, BuildingInfo info) {
        List<LightTodoQueue.Todo> lights = ctx.drainLightTodo();
        if (lights.isEmpty()) {
            return;
        }
        ChunkDriver driver = ctx.driver;
        LevelReader delegate = (LevelReader) driver.getRegion();
        LevelReader[] snapshotLevel = new LevelReader[1];
        List<DeferredLightPlacer.Planned> planned = DeferredLightPlacer.plan(
                ctx.coord.chunkX(), ctx.coord.chunkZ(), ctx.seed, lights, driver::getBlockAt,
                (marker, supportDirection, stateAt) -> {
                    LevelReader level = snapshotLevel(snapshotLevel, delegate, stateAt);
                    BlockPos supportPos = marker.relative(supportDirection);
                    net.minecraft.core.Direction exposedFace = supportDirection.getOpposite();
                    return stateAt.apply(supportPos).isFaceSturdy(level, supportPos, exposedFace);
                },
                (marker, attempt, stateAt) -> attempt.state()
                        .canSurvive(snapshotLevel(snapshotLevel, delegate, stateAt), marker));
        for (DeferredLightPlacer.Planned light : planned) {
            driver.currentAbsolute(light.pos()).block(light.state());
            updateNeeded(ctx, light.pos(), Block.UPDATE_CLIENTS);
        }
    }

    private static LevelReader snapshotLevel(LevelReader[] holder, LevelReader delegate,
                                             java.util.function.Function<BlockPos, BlockState> stateAt) {
        if (holder[0] == null) {
            holder[0] = DriverLevelReader.overlay(delegate, stateAt);
        }
        return holder[0];
    }

    private void doNormalChunk(ChunkGenContext ctx, BuildingInfo info, ChunkHeightmap heightmap, AvoidChunk avoidChunk) {
//        debugClearChunk(chunkX, chunkZ, primer);
        if ((avoidChunk != AvoidChunk.YES || !Config.AVOID_FLATTENING.get()) && profile.isDefault()) {
            correctTerrainShape(ctx, provider.getWorld(), info.coord, heightmap);
//            flattenChunkToCityBorder(chunkX, chunkZ);
        }

        Bridges.generateBridges(ctx, this, info);
        Highways.generateHighways(ctx, this, info);

        // Drawn at the scatter area's own anchor, so every chunk of one area agrees about which
        // pack's structure stands there - the same rule Scattered already applies to the reference
        // itself (issue #38). This is what makes scattered structures mix along with cities.
        ScatteredSettings scatteredSettings = provider.worldStyles()
                .atScatterArea(Scattered.areaAnchor(provider, info.coord)).getScatteredSettings();
        if (scatteredSettings != null) {
            if (!Scattered.avoidScattered(this, info)) {
                Scattered.generateScattered(ctx, this, info, scatteredSettings);
            }
        }
    }

    private void breakBlocksForDamageNew(ChunkGenContext ctx, int chunkX, int chunkZ, BuildingInfo info) {
        ChunkDriver driver = ctx.driver;
        int cx = chunkX << 4;
        int cz = chunkZ << 4;

        DamageArea damageArea = info.getDamageArea();

        float damageFactor = 1.0f;

        boolean hasCollectedDamage = false;
        float[][] collectedDamage = new float[16][16];

        int minSection = provider.getWorld().getMinY() >> 4;
        int maxSection = provider.getWorld().getMaxY() >> 4;
        for (int yy = minSection; yy <= maxSection; yy++) {
            java.util.List<dev.krona.urbex.worldgen.lost.Explosion> sectionExplosions = damageArea.explosionsIntersecting(yy);
            boolean hasExplosions = !sectionExplosions.isEmpty();
            for (int y = 0; y < 16; y++) {
                if (hasExplosions) {
                    int cury = yy * 16 + y;
                    for (int x = 0; x < 16; x++) {
                        driver.current(x, cury, 0);
                        for (int z = 0; z < 16; z++) {
                            BlockState d = driver.getBlock();
                            if (d != air || cury <= info.waterLevel) {
                                float damage = DamageArea.getDamage(sectionExplosions, cx + x, cury, cz + z) * damageFactor;
                                if (damage >= 0.001) {
                                    collectedDamage[x][z] += damage;
                                    hasCollectedDamage = true;
                                }
                            }
                            driver.incZ();
                        }
                    }
                }
                if (hasCollectedDamage) {
                    int cntDamaged = 0;
                    int cntAir = 0;
                    int cury = yy * 16 + y;
                    hasCollectedDamage = false;
                    for (int x = 0; x < 16; x++) {
                        driver.current(x, cury, 0);
                        for (int z = 0; z < 16; z++) {
                            BlockState d = driver.getBlock();
                            if (d != air || cury <= info.waterLevel) {
                                float damage = collectedDamage[x][z];
                                if (damage >= 0.001) {
                                    BlockState newd = damageArea.damageBlock(d, provider, ctx.tags, cx + x, cury, cz + z, damage, info.getCompiledPalette(), liquid);
                                    if (newd != d) {
                                        driver.block(newd);
                                        cntDamaged++;
                                    }
                                }
                            } else {
                                cntAir++;
                            }
                            driver.incZ();
//                            collectedDamage[x][z] -= .75f;
                            collectedDamage[x][z] /= 1.4f;
//                            collectedDamage[x][z] = 0;
                            if (collectedDamage[x][z] <= 0) {
                                collectedDamage[x][z] = 0;
                            } else {
                                hasCollectedDamage = true;
                            }
                        }
                    }

                    int tot = cntDamaged + cntAir;
                    if (tot > 250) {
                        damageFactor = 200;
                    } else if (tot > 220) {
                        damageFactor = damageFactor * 1.4f;
                    } else if (tot > 180) {
                        damageFactor = damageFactor * 1.2f;
                    }

                }
            }
        }
    }

    public String getRandomPart(ChunkGenContext ctx, List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        } else {
            return parts.get(ctx.rng(Rng.Purpose.PARTS).nextInt(parts.size()));
        }
    }

    public void clearRange(ChunkGenContext ctx, BuildingInfo info, int x, int z, int height1, int height2, boolean dowater) {
        ChunkDriver driver = ctx.driver;
        if (dowater) {
            // Special case for drowned city
            driver.setBlockRange(x, height1, z, info.waterLevel, liquid);
            driver.setBlockRangeToAir(x, info.waterLevel + 1, z, height2);
        } else {
            driver.setBlockRangeToAir(x, height1, z, height2);
        }
    }

    public void clearRange(ChunkGenContext ctx, BuildingInfo info, int x, int z, int height1, int height2, boolean dowater, Predicate<BlockState> test) {
        ChunkDriver driver = ctx.driver;
        if (dowater) {
            // Special case for drowned city
            driver.setBlockRange(x, height1, z, info.waterLevel, liquid, test);
            driver.setBlockRangeToAir(x, info.waterLevel + 1, z, height2, test);
        } else {
            driver.setBlockRangeToAir(x, height1, z, height2, test);
        }
    }

    /**
     * These three are asked about chunks other than the one being generated - a chunk interpolates
     * its terrain fix against its eight neighbours - so they take the seed and the coordinate they
     * are being asked about rather than a {@link ChunkGenContext}.
     * <p>
     * {@code getRandomizedOffset} takes its purpose from the caller because it is asked twice at
     * one coordinate, for the lower and the upper bound of the same mesh. One purpose would tie
     * them together, and neither may be shared with the street-type pick, which is drawn at the
     * same address.
     */
    public static int getRandomizedOffset(long seed, int chunkX, int chunkZ, int min, int max, Rng.Purpose purpose) {
        return Rng.at(seed, chunkX, chunkZ, purpose).nextInt(max - min + 1) + min;
    }

    public static int getHeightOffsetL1(long seed, int chunkX, int chunkZ) {
        return Rng.at(seed, chunkX, chunkZ, Rng.Purpose.TERRAIN_L1).nextInt(5);
    }

    public static int getHeightOffsetL2(long seed, int chunkX, int chunkZ) {
        return Rng.at(seed, chunkX, chunkZ, Rng.Purpose.TERRAIN_L2).nextInt(5);
    }

    /*
     * This routine is used on a normal (non-city) chunk to make sure the landscape nicely fits
     * with any possible adjacent city chunks. It works by creating two meshes that are overlayed
     * on the terrain. Meshes are defined at chunk corners. Every chunk corner has a corresponding
     * height on the two meshes.
     *
     * The upper mesh indicates the maximum height the terrain is allowed to go. If a certain chunk
     * corner is not adjacent to any city chunk or is not adjacent to any normal chunk then there is
     * no maximum height and in that case we set it to 100000. Otherwise (if the chunk corner
     * is adjacent to mixed chunks) the maximum allowed height of the terrain is equal to the minimum
     * height of all the city chunks (with minimum height we mean the lower city level or the height
     * of the first floor).
     *
     * The lower mesh indicates the minimum height the terrain is allowed to go. Same as with the upper
     * mesh there is no minimum in case the chunk corner is not a mixed type corner. Otherwise the
     * minimum height is going to be some (configurable) offset below the minimum lower city level.
     *
     * Every normal chunk is made to fit between the lower and the upper mesh by moving down
     * or up the top layer (6 thick) of the terrain. In a chunk these heights are interpolated
     * (bilinear interpolation).
     */
    private void correctTerrainShape(ChunkGenContext ctx, WorldGenLevel level, ChunkCoord coord, ChunkHeightmap heightmap) {
        BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
        BuildingInfo.MinMax mm00 = info.getDesiredMaxHeightL2();
        BuildingInfo.MinMax mm10 = info.getXmax().getDesiredMaxHeightL2();
        BuildingInfo.MinMax mm01 = info.getZmax().getDesiredMaxHeightL2();
        BuildingInfo.MinMax mm11 = info.getXmax().getZmax().getDesiredMaxHeightL2();

        int min = level.getMinY();
        int max = level.getMaxY() + 1;
        int heightmapH = Short.MIN_VALUE;

        float min00 = mm00.min;
        float min10 = mm10.min;
        float min01 = mm01.min;
        float min11 = mm11.min;
        float max00 = mm00.max;
        float max10 = mm10.max;
        float max01 = mm01.max;
        float max11 = mm11.max;
        if (max00 < max || max10 < max || max01 < max || max11 < max ||
                min00 < max || min10 < max || min01 < max || min11 < max) {
            // We need to fit the terrain between the upper and lower mesh here
            int maxHeightP = heightmap.getHeight() + 90;
            int minHeightP = heightmap.getHeight() - 90;
            if (max00 >= max) {
                max00 = maxHeightP;
            }
            if (max10 >= max) {
                max10 = maxHeightP;
            }
            if (max01 >= max) {
                max01 = maxHeightP;
            }
            if (max11 >= max) {
                max11 = maxHeightP;
            }
            if (min00 >= max) {
                min00 = minHeightP;
            }
            if (min10 >= max) {
                min10 = minHeightP;
            }
            if (min01 >= max) {
                min01 = minHeightP;
            }
            if (min11 >= max) {
                min11 = minHeightP;
            }

            for (int x = 0; x < 16; x++) {
                // Bilinear interpolation
                float factor = (15.0f - x) / 15.0f;
                float maxh0 = max11 + (max01 - max11) * factor;
                float maxh1 = max10 + (max00 - max10) * factor;
                float minh0 = min11 + (min01 - min11) * factor;
                float minh1 = min10 + (min00 - min10) * factor;
                for (int z = 0; z < 16; z++) {
                    float maxheight = maxh0 + (maxh1 - maxh0) * (15.0f - z) / 15.0f;
                    if (maxheight > max) {
                        maxheight = max;
                    }
                    int maxTouchedY = moveDown(ctx, x, z, (int) maxheight, max);

                    if (maxTouchedY == Short.MIN_VALUE) {
                        float minheight = minh0 + (minh1 - minh0) * (15.0f - z) / 15.0f;
                        if (minheight < min) {
                            minheight = min;
                        }
                        maxTouchedY = moveUp(ctx, x, z, (int) minheight, info.waterLevel > info.groundLevel);
                    }
                    if (maxTouchedY != Short.MIN_VALUE && x == 8 && z == 8) {
                        // Only adjust heightmap for center value
                        heightmapH = Math.max(heightmapH, maxTouchedY);
                    }
                }
            }
            if (heightmapH != Short.MIN_VALUE) {
                heightmap.setHeight(heightmapH);
            }
        }
    }

    // Return true if state is air or liquid
    public static boolean isEmpty(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (state.is(Blocks.WATER)) {
            return true;
        }
        if (state.is(Blocks.LAVA)) {
            return true;
        }
        return false;
    }

    // Return true if state is Empty or Plant based - stops (most) funny tree/mushroom action on chunk borders
    private static boolean isFoliageOrEmpty(TagSnapshot tags, BlockState state) {
        if (isEmpty(state)) {
            return true;
        }
        return tags.isFoliage(state);
    }

    /**
     * Fill base blocks downwards from 'y' until solid ground (or the bedrock layer) is reached,
     * so that whatever rests on top of it is not left hanging in the air. This is the same fill
     * fillToBedrockStreetBlock() applies under streets.
     */
    private void fillSupportBelow(ChunkGenContext ctx, int x, int z, int y) {
        ChunkDriver driver = ctx.driver;
        int lowest = provider.getWorld().getMinY() + profile.BEDROCK_LAYER;
        driver.current(x, y, z);
        while (driver.getY() > lowest && isEmpty(driver.getBlock())) {
            driver.block(base);
            driver.decY();
        }
    }

    // Return the new max height of the chunk in this column. Or Short.MIN_VALUE if nothing was done
    private int moveUp(ChunkGenContext ctx, int x, int z, int height, boolean dowater) {
        ChunkDriver driver = ctx.driver;
        int maxYTouched = Short.MIN_VALUE;       // Max Y that we touched
        // Find the first non-empty block starting at the given height
        driver.current(x, height, z);
        int minHeight = provider.getWorld().getMinY();
        // We assume here we are not in a void chunk
        while (isFoliageOrEmpty(ctx.tags, driver.getBlock()) && driver.getY() > minHeight) {
            driver.decY();
        }

        if (driver.getY() >= height) {
            return maxYTouched; // Nothing to do
        }

        int idx = driver.getY();    // Points to non-empty block below the empty block
        driver.current(x, height, z);
        while (idx > 0) {
            BlockState blockToMove = driver.getBlock(x, idx, z);
            if (blockToMove.isAir() || blockToMove.getBlock() == Blocks.BEDROCK) {
                break;
            }
            if (maxYTouched == Short.MIN_VALUE) {
                maxYTouched = idx;
            }
            driver.block(blockToMove);
            driver.decY();
            idx--;
        }
        return maxYTouched;
    }

    // Return the new max height of the chunk in this column. Or Short.MIN_VALUE if nothing was done
    private int moveDown(ChunkGenContext ctx, int x, int z, int height, int maxBuildLimit) {
        ChunkDriver driver = ctx.driver;
        BlockState[] buffer = ctx.moveDownBuffer;
        int maxYTouched = Short.MIN_VALUE;       // Max Y that we touched
        int y = maxBuildLimit-1;
        driver.current(x, y, z);
        // We assume here we are not in a void chunk
        while (isEmpty(driver.getBlock()) && driver.getY() > height) {
            driver.decY();
        }

        if (driver.getY() <= height) {
            return maxYTouched; // Nothing to do
        }

        // We arrived at our first non-air block
        int bufferIdx = 0;
        while (driver.getY() >= height) {
            if (bufferIdx < buffer.length) {
                buffer[bufferIdx++] = driver.getBlock();
            }
            driver.block(air);
            driver.decY();
        }

        maxYTouched = driver.getY();
        int idx = 0;
        while (idx < bufferIdx && driver.getY() > 0) {
            driver.block(buffer[idx++]);
            driver.decY();
        }

        // The buffer only carried the top few blocks of whatever used to be here, and nothing
        // above this point looked at what is underneath. Whenever the column we just moved that
        // surface down onto is empty - an overhang or cliff shoulder over a carved cavern, or a
        // sampled heightmap that disagrees with the local terrain - the relocated surface is
        // left hanging in the air, and so is everything the city then builds on top of it.
        fillSupportBelow(ctx, x, z, driver.getY());

//
//        if (dowater) {
//            // Special case for drowned city
//            driver.setBlockRange(x, height1, z, info.waterLevel, liquid);
//            driver.setBlockRange(x, info.waterLevel+1, z, height2, air);
//        } else {
//            driver.setBlockRange(x, height1, z, height2, air);
//        }
        return maxYTouched;
    }


    public static boolean isWaterBiome(IDimensionInfo provider, ChunkCoord coord) {
        BiomeInfo biomeInfo = BiomeInfo.getBiomeInfo(provider, coord);
        Holder<Biome> mainBiome = biomeInfo.getMainBiome();
        return isWaterBiome(mainBiome);
    }

    private static boolean isWaterBiome(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_BEACH) || biome.is(BiomeTags.IS_RIVER);
    }

    /**
     * This function returns the height at a given point in this chunk
     * If the point is at a border and the adjacent chunk at that point happens to be lower
     * then this will return the minimum height
     */
    public int getMinHeightAt(BuildingInfo info, int x, int z, ChunkHeightmap heightmap) {
        int height = heightmap.getHeight();
        WorldGenLevel world = info.provider.getWorld();
        int adjacent;
        if (x == 0) {
            if (z == 0) {
                adjacent = getHeightmap(info.coord.northWest(), world).getHeight();
            } else if (z == 15) {
                adjacent = getHeightmap(info.coord.southWest(), world).getHeight();
            } else {
                adjacent = getHeightmap(info.coord.west(), world).getHeight();
            }
        } else if (x == 15) {
            if (z == 0) {
                adjacent = getHeightmap(info.coord.northEast(), world).getHeight();
            } else if (z == 15) {
                adjacent = getHeightmap(info.coord.southEast(), world).getHeight();
            } else {
                adjacent = getHeightmap(info.coord.east(), world).getHeight();
            }
        } else if (z == 0) {
            adjacent = getHeightmap(info.coord.north(), world).getHeight();
        } else if (z == 15) {
            adjacent = getHeightmap(info.coord.south(), world).getHeight();
        } else {
            return height;
        }
        return Math.min(height, adjacent);
    }

    public ChunkHeightmap getHeightmap(ChunkCoord chunk, @Nonnull WorldGenLevel world) {
        int heightSampleSize = Config.HEIGHT_SAMPLE_SIZE.get();
        // The block this chunk shares a sampled height with, and the coordinate that height is taken
        // at. Both come from HeightSampleGrid so that the tiling is a partition and the sampled
        // coordinate is a function of the block rather than of whichever chunk asked first - see the
        // note there for what the old arithmetic did at the origin, and issue #126.
        int top = HeightSampleGrid.anchor(chunk.chunkX(), heightSampleSize);
        int left = HeightSampleGrid.anchor(chunk.chunkZ(), heightSampleSize);
        ChunkCoord sampler = new ChunkCoord(chunk.dimension(),
                HeightSampleGrid.sampler(top, heightSampleSize),
                HeightSampleGrid.sampler(left, heightSampleSize));
        // No lock. The heightmap is a pure function of the generator and the coordinate, so two
        // threads that race on the same chunk build two equal heightmaps and one of them is thrown
        // away; a third thread reading the cache sees whichever was published, and they agree.
        //
        // That holds only as long as nobody writes to what this returns. The instance is shared,
        // so a caller that needs to mutate one - correctTerrainShape's setHeight,
        // calculateAccurateHeight - must take a copy first (the copy constructor exists for this).
        // Mutating the published instance would be a data race on a plain int field and, worse,
        // would make what a neighbouring chunk reads depend on generation order.
        TimedCache<ChunkCoord, ChunkHeightmap> cachedHeightmaps = provider.caches().heightmap;
        ChunkHeightmap cached = cachedHeightmaps.get(chunk);
        if (cached != null) {
            return cached;
        }
        ChunkHeightmap heightmap = new ChunkHeightmap(profile.LANDSCAPE_TYPE, profile.GROUNDLEVEL);
        generateHeightmap(sampler.chunkX(), sampler.chunkZ(), world, heightmap);
        if (heightSampleSize > 1) {
            for (int i = 0; i < heightSampleSize; i++) {
                for (int j = 0; j < heightSampleSize; j++) {
                    ChunkCoord sampleKey = new ChunkCoord(chunk.dimension(), top + i, left + j);
                    cachedHeightmaps.putIfAbsent(sampleKey, new ChunkHeightmap(heightmap));
                }
            }
        } else {
            cachedHeightmaps.putIfAbsent(chunk, heightmap);
        }
        return heightmap;
    }

    private void generateHeightmap(int chunkX, int chunkZ, WorldGenLevel region, ChunkHeightmap heightmap) {
        ServerChunkCache chunkProvider = region.getLevel().getChunkSource();
        ChunkGenerator generator = chunkProvider.getGenerator();
        int cx = chunkX << 4;
        int cz = chunkZ << 4;
        RandomState randomState = chunkProvider.randomState();

        int height = generator.getBaseHeight(cx + 8, cz + 8, Heightmap.Types.OCEAN_FLOOR_WG, region, randomState);
        heightmap.update(height);
    }

    private void doCityChunk(ChunkGenContext ctx, BuildingInfo info, ChunkHeightmap heightmap, ChunkAccess chunk) {
        ChunkDriver driver = ctx.driver;
        boolean building = info.hasBuilding;

        if (info.profile.isDefault()) {
            int minHeight = info.provider.getWorld().getMinY();
            BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    driver.setBlockRange(x, minHeight, z, minHeight + info.profile.BEDROCK_LAYER, bedrock);
                }
            }

            if (info.waterLevel > info.groundLevel) {
                // Special case for a high water level
                for (int x = 0; x < 16; ++x) {
                    for (int z = 0; z < 16; ++z) {
                        driver.setBlockRange(x, info.groundLevel, z, info.waterLevel, liquid);
                    }
                }
            }
        }

        // City surface leveling - for prettier cities
        // Note: Better results may be achieved with terrain noise adjustment (like how newer structures do it)
        if (profile.isDefault()) {
            int ground = info.getCityGroundLevel();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int maxTouchedY = moveDown(ctx, x, z, ground + 1, provider.getWorld().getMaxY() + 1);
                    if (maxTouchedY == Short.MIN_VALUE) {
                        moveUp(ctx, x, z, ground, info.waterLevel > info.groundLevel);
                    }
                }
            }
        }

        if (building) {
            generateBuilding(ctx, info, heightmap, chunk);
        } else {
            generateStreet(ctx, info, heightmap);
        }

        if (info.profile.RUIN_CHANCE > 0.0) {
            generateRuins(ctx, info);
        }

        int levelX = info.getHighwayXLevel();
        int levelZ = info.getHighwayZLevel();
        if (!building) {
            Railway.RailChunkInfo railInfo = info.getRailInfo();
            if (levelX < 0 && levelZ < 0 && !railInfo.getType().isSurface()
                    && info.getStreetSlopeDirection() == null) {
                generateStreetDecorations(ctx, info);
            }
        }
        if (levelX >= 0 || levelZ >= 0) {
            Highways.generateHighways(ctx, this, info);
        }

        if (info.profile.RUBBLELAYER) {
            if (!info.hasBuilding || info.ruinHeight >= 0) {
                generateRubble(ctx, info);
            }
        }

        Stuff.generateStuff(ctx, this, info);
    }

    private void generateStreetDecorations(ChunkGenContext ctx, BuildingInfo info) {
        Direction stairDirection = info.getActualStairDirection();
        if (stairDirection != null) {
            BuildingPart stairs = info.stairType;
            if (stairs != null) {
                Transform transform;
                int oy = info.getCityGroundLevel() + 1;
                transform = switch (stairDirection) {
                    case XMIN -> Transform.ROTATE_NONE;
                    case XMAX -> Transform.ROTATE_180;
                    case ZMIN -> Transform.ROTATE_90;
                    case ZMAX -> Transform.ROTATE_270;
                };

                generatePart(ctx, info, stairs, transform, 0, oy, 0, HardAirSetting.AIR);
            }
        }
    }

    private int countNotEmpty(ChunkGenContext ctx, int y, int max) {
        ChunkDriver driver = ctx.driver;
        int cnt = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (driver.getBlock(x, y, z) != air) {
                    cnt++;
                    if (cnt >= max) {
                        return cnt;
                    }
                }
            }
        }
        return cnt;
    }

    /// Fix floating blocks after an explosion
    private void fixAfterExplosion(ChunkGenContext ctx, BuildingInfo info) {
        ChunkDriver driver = ctx.driver;
        if (info.profile.isCavern() && !info.hasBuilding) {
            // In a cavern we only do this correction when there is a building
            return;
        }

        int start = info.getDamageArea().getLowestExplosionHeight();
        if (start == -1) {
            // Nothing is affected
            return;
        }
        int end = info.getDamageArea().getHighestExplosionHeight();

        for (int y = start; y <= end; y++) {
            int count = countNotEmpty(ctx, y, 20);
            if (count < 16) {   // @todo configurable?
                // (Almost) empty! That means everything above this can be deleted
                // Except in a cavern, there we only delete the building
                if (info.profile.isCavern()) {
                    // We know we have a building
                    int maxY = info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            driver.setBlockRangeToAir(x, y + 1, z, maxY);
                        }
                    }
                } else {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            driver.setBlockRangeToAir(x, y + 1, z, provider.getWorld().getMaxY() + 1);
                        }
                    }
                }
                break;
            }
        }
    }

    private void generateRubble(ChunkGenContext ctx, BuildingInfo info) {
        ChunkDriver driver = ctx.driver;
        int chunkX = info.coord.chunkX();
        int chunkZ = info.coord.chunkZ();
        double[] rubbleBuffer = ctx.buffers.rubble = this.rubbleNoise.getRegion(ctx.buffers.rubble, (chunkX << 4), (chunkZ << 4), 16, 16, 1.0 / 16.0, 1.0 / 16.0, 1.0D);
        double[] leavesBuffer = ctx.buffers.leaves = this.leavesNoise.getRegion(ctx.buffers.leaves, (chunkX << 6), (chunkZ << 6), 16, 16, 1.0 / 64.0, 1.0 / 64.0, 4.0D);

        Set<BlockState> possibleRandomDirts = getPossibleRandomDirts(ctx, info, info.getCompiledPalette());
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                double vr = info.profile.RUBBLE_DIRT_SCALE < 0.01f ? 0 : rubbleBuffer[x + z * 16] / info.profile.RUBBLE_DIRT_SCALE;
                double vl = info.profile.RUBBLE_LEAVE_SCALE < 0.01f ? 0 : leavesBuffer[x + z * 16] / info.profile.RUBBLE_LEAVE_SCALE;
                if (vr > .5 || vl > .5) {
                    int height = getInterpolatedHeight(info, x, z);
                    driver.current(x, height, z);
                    BlockState c = driver.getBlockDown();
                    if (c != air && c != liquid) {
                        for (int i = 0; i < vr; i++) {
                            if (isEmpty(driver.getBlock())) {
                                driver.add(getRandomDirt(ctx, info, info.getCompiledPalette()));
                            } else {
                                driver.incY();
                            }
                        }
                    }
                    //first round may not have generated this - stops crash on create world
                    BlockState leafBaseState = driver.getBlockDown();
                    if (leafBaseState == base || possibleRandomDirts.contains(leafBaseState)) {
                        for (int i = 0; i < vl; i++) {
                            if (isEmpty(driver.getBlock())) {
                                driver.add(getRandomLeaf(ctx, info, info.getCompiledPalette()));
                            } else {
                                driver.incY();
                            }
                        }
                    }
                }
            }
        }
    }

    private int getInterpolatedHeight(BuildingInfo info, int x, int z) {
        if (x < 8 && z < 8) {
            // First quadrant
            float h00 = info.getXmin().getZmin().getCityGroundLevelOutsideLower();
            float h10 = info.getZmin().getCityGroundLevelOutsideLower();
            float h01 = info.getXmin().getCityGroundLevelOutsideLower();
            float h11 = info.getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x + 8, z + 8);
        } else if (x >= 8 && z < 8) {
            // Second quadrant
            float h00 = info.getZmin().getCityGroundLevelOutsideLower();
            float h10 = info.getXmax().getZmin().getCityGroundLevelOutsideLower();
            float h01 = info.getCityGroundLevelOutsideLower();
            float h11 = info.getXmax().getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x - 8, z + 8);
        } else if (x < 8 && z >= 8) {
            // Third quadrant
            float h00 = info.getXmin().getCityGroundLevelOutsideLower();
            float h10 = info.getCityGroundLevelOutsideLower();
            float h01 = info.getXmin().getZmax().getCityGroundLevelOutsideLower();
            float h11 = info.getZmax().getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x + 8, z - 8);
        } else {
            // Fourth quadrant
            float h00 = info.getCityGroundLevelOutsideLower();
            float h10 = info.getXmax().getCityGroundLevelOutsideLower();
            float h01 = info.getZmax().getCityGroundLevelOutsideLower();
            float h11 = info.getXmax().getZmax().getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x - 8, z - 8);
        }
    }

    private int bipolate(float h00, float h10, float h01, float h11, int dx, int dz) {
        float factor = (15.0f - dx) / 15.0f;
        float h0 = h00 + (h10 - h00) * factor;
        float h1 = h01 + (h11 - h01) * factor;
        float h = h0 + (h1 - h0) * (15.0f - dz) / 15.0f;
        return (int) h;
    }


    /**
     * A roll in {@code [0, 1)} for the block the driver is currently on. Addressed by that
     * position, so a loop that walks two blocks further than it did last time changes those two
     * blocks and nothing else.
     */
    private static float rollHere(ChunkGenContext ctx, ChunkDriver driver, Rng.Purpose purpose) {
        return Rng.floatAtPos(ctx.seed, driver.getX(), driver.getY(), driver.getZ(), purpose);
    }

    private void generateRuins(ChunkGenContext ctx, BuildingInfo info) {
        if (info.ruinHeight < 0) {
            return;
        }

        ChunkDriver driver = ctx.driver;
        int chunkX = info.coord.chunkX();
        int chunkZ = info.coord.chunkZ();
        double d0 = 0.03125D;
        double[] ruinBuffer = ctx.buffers.ruin = this.ruinNoise.getRegion(ctx.buffers.ruin, (chunkX << 4), (chunkZ << 4), 16, 16, d0 * 2.0D, d0 * 2.0D, 1.0D);
        double[] leavesBuffer = ctx.buffers.leaves;
        boolean doLeaves = info.profile.RUBBLELAYER;
        if (doLeaves) {
            leavesBuffer = ctx.buffers.leaves = this.leavesNoise.getRegion(ctx.buffers.leaves, (chunkX << 6), (chunkZ << 6), 16, 16, 1.0 / 64.0, 1.0 / 64.0, 4.0D);
        }

        int baseheight = (int) (info.getCityGroundLevel() + 1 + (info.ruinHeight * info.getNumFloors() * FLOORHEIGHT));

        CompiledPalette palette = info.getCompiledPalette();
        BlockState ironbarsState = Blocks.IRON_BARS.defaultBlockState();
        Character infobarsChar = info.getCityStyle().getIronbarsBlock();
        Supplier<BlockState> ironbars = infobarsChar == null ? () -> ironbarsState : () -> ctx.paletteHere(palette, infobarsChar);
        Set<BlockState> infoBarSet = infobarsChar == null ? Collections.singleton(ironbarsState) : palette.getAll(infobarsChar);
        Predicate<BlockState> checkIronbars = infobarsChar == null ? s -> s == ironbarsState : infoBarSet::contains;
        Character rubbleBlock = info.getBuilding().getRubbleBlock();

        int maxBuildHeight = info.provider.getWorld().getMaxY() + 1;
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                double v = ruinBuffer[x + z * 16];
//                double v = ruinNoise.getValue(x, z) / 16.0;
                int height = baseheight + (int) v;
                driver.current(x, height, z);
                height = info.getMaxHeight() + 10 - height;
                if (height > maxBuildHeight - 2) {
                    height = maxBuildHeight - 2;
                }
                int vl = 0;
                if (doLeaves) {
                    vl = (int) (info.profile.RUBBLE_LEAVE_SCALE < 0.01f ? 0 : leavesBuffer[x + z * 16] / info.profile.RUBBLE_LEAVE_SCALE);
//                    vl = (int) (info.profile.RUBBLE_LEAVE_SCALE < 0.01f ? 0 : leavesNoise.getValue(x / 64.0, z / 64.0) / 4.0 * info.profile.RUBBLE_LEAVE_SCALE);
                }
                boolean doRubble = palette.isDefined(rubbleBlock);
                while (height > 0) {
                    BlockState damage = palette.canBeDamagedToIronBars(driver.getBlock());
                    BlockState c = driver.getBlockDown();

                    if (doRubble && !checkIronbars.test(c) && c != air && c != liquid && rollHere(ctx, driver, Rng.Purpose.RUINS) < .2f) {      // @todo hardcoded random
                        doRubble = false;
                        driver.add(ctx.paletteHere(palette, rubbleBlock));
                    } else if ((damage != null || checkIronbars.test(c)) && c != air && c != liquid && rollHere(ctx, driver, Rng.Purpose.RUINS_BARS) < .2f) {    // @todo hardcoded random
                        driver.add(ironbars.get());
                    } else {
                        if (vl > 0) {
                            c = driver.getBlockDown();
                            while (isEmpty(c)) {
                                driver.decY();
                                height++;   // Make sure we keep on filling with air a bit longer because we are lowering here
                                c = driver.getBlockDown();
                            }
                            driver.add(getRandomLeaf(ctx, info, palette));
                            vl--;
                        } else {
                            driver.add(air);
                        }
                    }
                    height--;
                }
            }
        }
    }

    private void generateStreet(ChunkGenContext ctx, BuildingInfo info, ChunkHeightmap heightmap) {
        ChunkDriver driver = ctx.driver;
        boolean xRail = info.hasXCorridor();
        boolean zRail = info.hasZCorridor();
        if (xRail || zRail) {
            Corridors.generateCorridors(ctx, this, info, xRail, zRail);
        }

        Railway.RailChunkInfo railInfo = info.getRailInfo();
        boolean canDoStreetOrPark = info.getHighwayXLevel() != info.cityLevel && info.getHighwayZLevel() != info.cityLevel
                && railInfo.getType() != RailChunkType.STATION_SURFACE
                && (railInfo.getType() != RailChunkType.STATION_EXTENSION_SURFACE || railInfo.getLevel() < info.cityLevel);

        if (canDoStreetOrPark) {
            int height = info.getCityGroundLevel();
            // In default landscape type we clear the landscape on top of the building
//            if (profile.isDefault()) {
//                clearToMax(info, heightmap, height);
//            }

            Direction streetSlopeDirection = info.getStreetSlopeDirection();
            BuildingInfo.StreetType streetType = info.streetType;
            boolean elevated = info.isElevatedParkSection();
            if (elevated) {
                Character elevationBlock = info.getCityStyle().getParkElevationBlock();
                BlockState elevation = ctx.paletteAt(info.getCompiledPalette(), elevationBlock, 0, height, 0);
                for (int x = 0; x < 16; ++x) {
                    driver.current(x, height, 0);
                    for (int z = 0; z < 16; ++z) {
                        driver.block(elevation).incZ();
                    }
                }
                boolean parkElevation = info.profile.PARK_ELEVATION;
                if (info.getCityStyle().getParkElevation() != null) {
                    parkElevation = info.getCityStyle().getParkElevation();
                }
                if (parkElevation) {
                    height++;
                }
            }

            // No re-roll here any more. The content decision is authoritative: a planned road is
            // NORMAL, an open lot is whatever its addressed park roll said, and re-rolling was what
            // used to clobber the PARK decision neighbours read through isElevatedParkSection()
            // (issue #36).
            switch (streetType) {
                case NORMAL -> {
                    if (streetSlopeDirection == null) {
                        generateNormalStreetSection(ctx, info, height);
                    } else {
                        generateStreetSlopeSection(ctx, info, height, streetSlopeDirection);
                    }
                }
                case PARK -> generateParkSection(ctx, info, height, elevated);
            }
            height++;

            // A sloped chunk is a ramp from end to end. Everything that would stand on the surface -
            // a fountain, a park part, vegetation, a building front reaching out over the pavement -
            // is suppressed, because on a ramp it would sit in mid-air or in the middle of the route.
            if (streetSlopeDirection == null) {
                if (streetType == BuildingInfo.StreetType.PARK || info.fountainType != null) {
                    BuildingPart part;
                    if (streetType == BuildingInfo.StreetType.PARK) {
                        part = info.parkType;
                    } else {
                        part = info.fountainType;
                    }
                    if (part != null) {
                        generatePart(ctx, info, part, Transform.ROTATE_NONE, 0, height, 0, HardAirSetting.AIR);
                    }
                }

                generateRandomVegetation(ctx, info, height);

                generateFrontPart(ctx, info, height, info.getXmin(), Transform.ROTATE_NONE);
                generateFrontPart(ctx, info, height, info.getZmin(), Transform.ROTATE_90);
                generateFrontPart(ctx, info, height, info.getXmax(), Transform.ROTATE_180);
                generateFrontPart(ctx, info, height, info.getZmax(), Transform.ROTATE_270);
            }
        }

        generateBorders(ctx, info, canDoStreetOrPark, heightmap);
    }

    private void generateBorders(ChunkGenContext ctx, BuildingInfo info, boolean canDoParks, ChunkHeightmap heightmap) {
        Character borderBlock = info.getCityStyle().getBorderBlock();

        if (info.profile.isFloating()) {
            fillMainStreetBlock(ctx, info, borderBlock, 3);
        } else if (info.profile.isCavern()) {
            fillMainStreetBlock(ctx, info, borderBlock, 2);
        } else {
            fillToBedrockStreetBlock(ctx, info);
        }

        if (doBorder(info, Direction.XMIN)) {
            int x = 0;
            for (int z = 0; z < 16; z++) {
                generateBorder(ctx, info, canDoParks, x, z, Direction.XMIN.get(info), heightmap);
            }
        }
        if (doBorder(info, Direction.XMAX)) {
            int x = 15;
            for (int z = 0; z < 16; z++) {
                generateBorder(ctx, info, canDoParks, x, z, Direction.XMAX.get(info), heightmap);
            }
        }
        if (doBorder(info, Direction.ZMIN)) {
            int z = 0;
            for (int x = 0; x < 16; x++) {
                generateBorder(ctx, info, canDoParks, x, z, Direction.ZMIN.get(info), heightmap);
            }
        }
        if (doBorder(info, Direction.ZMAX)) {
            int z = 15;
            for (int x = 0; x < 16; x++) {
                generateBorder(ctx, info, canDoParks, x, z, Direction.ZMAX.get(info), heightmap);
            }
        }
    }

    /**
     * Fill base blocks under streets to bedrock
     */
    private void fillToBedrockStreetBlock(ChunkGenContext ctx, BuildingInfo info) {
        ChunkDriver driver = ctx.driver;
        // Base blocks below streets
        int minHeight = info.provider.getWorld().getMinY();
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                int y = info.getCityGroundLevel() - 1;
                driver.current(x, y, z);
                while (driver.getY() > (minHeight + info.profile.BEDROCK_LAYER) && isEmpty(driver.getBlock())) {
                    driver.block(base);
                    driver.decY();
                }
//                driver.setBlockRange(x, info.profile.BEDROCK_LAYER, z, info.getCityGroundLevel(), baseChar);
            }
        }
    }

    /**
     * Fill a main street block with base blocks and border blocks at the bottom
     */
    private void fillMainStreetBlock(ChunkGenContext ctx, BuildingInfo info, Character borderBlock, int offset) {
        ChunkDriver driver = ctx.driver;
        BlockState border = ctx.paletteAt(info.getCompiledPalette(), borderBlock, 0, info.getCityGroundLevel() - offset, 0);
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                driver.setBlockRange(x, info.getCityGroundLevel() - (offset - 1), z, info.getCityGroundLevel(), base);
                driver.current(x, info.getCityGroundLevel() - offset, z).block(border);
            }
        }
    }

    /**
     * Generate a single border column for one side of a street block
     */
    private void generateBorder(ChunkGenContext ctx, BuildingInfo info, boolean canDoParks, int x, int z, BuildingInfo adjacent, ChunkHeightmap heightmap) {
        ChunkDriver driver = ctx.driver;
        Character borderBlock = info.getCityStyle().getBorderBlock();
        Character wallBlock = info.getCityStyle().getWallBlock();
        BlockState wall = ctx.paletteAt(info.getCompiledPalette(), wallBlock, x, info.getCityGroundLevel() + 1, z);

        if (info.profile.isFloating()) {
                setBlocksFromPalette(ctx, x, info.getCityGroundLevel() - 3, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
                if (isCorner(x, z)) {
                    generateBorderSupport(ctx, info, wall, x, z, 3, heightmap);
                }
        } else if (info.profile.isCavern()) {
                setBlocksFromPalette(ctx, x, info.getCityGroundLevel() - 2, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
                if (isCorner(x, z)) {
                    generateBorderSupport(ctx, info, wall, x, z, 2, heightmap);
                }
        } else {
            int y = getMinHeightAt(info, x, z, heightmap);
            if (y < info.getCityGroundLevel() + 1) {
                setBlocksFromPalette(ctx, x, y - 1, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
            } else {
                setBlocksFromPalette(ctx, x, info.getCityGroundLevel() - 3, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
            }
        }
        if (canDoParks) {
            if (!borderNeedsConnectionToAdjacentChunk(info, x, z)) {
                driver.current(x, info.getCityGroundLevel() + 1, z).block(wall);
            } else {
                driver.current(x, info.getCityGroundLevel() + 1, z).block(air);
            }
        }
    }

    /**
     * Generate a column of wall blocks (and stone below that in water)
     */
    private void generateBorderSupport(ChunkGenContext ctx, BuildingInfo info, BlockState wall, int x, int z, int offset, ChunkHeightmap heightmap) {
        ChunkDriver driver = ctx.driver;
        int height = heightmap.getHeight();
        if (height > 1) {
            // None void
            int y = info.getCityGroundLevel() - offset - 1;
            driver.current(x, y, z);
            while (y > 1 && driver.getBlock() == air) {
                driver.block(wall).decY();
                y--;
            }
            while (y > 1 && driver.getBlock() == liquid) {
                driver.block(base).decY();
                y--;
            }
        }
    }

    private int generateFrontPart(ChunkGenContext ctx, BuildingInfo info, int height, BuildingInfo adj, Transform rot) {
        if (info.hasFrontPartFrom(adj)) {
            return generatePart(ctx, adj, adj.frontType, rot, 0, height, 0, HardAirSetting.AIR);
        }
        return height;
    }

    private void generateRandomVegetation(ChunkGenContext ctx, BuildingInfo info, int height) {
        ChunkDriver driver = ctx.driver;

        if (info.getXmin().hasBuilding) {
            for (int x = 0; x < info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; x++) {
                for (int z = 0; z < 16; z++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS + 1 - x));
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION) < v && cnt < 30) {
                        driver.add(getRandomLeaf(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getXmax().hasBuilding) {
            for (int x = 15 - info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; x < 15; x++) {
                for (int z = 0; z < 16; z++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (x - 14 + info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS));
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION_XMAX) < v && cnt < 30) {
                        driver.add(getRandomLeaf(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getZmin().hasBuilding) {
            for (int z = 0; z < info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; z++) {
                for (int x = 0; x < 16; x++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS + 1 - z));
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION_ZMIN) < v && cnt < 30) {
                        driver.add(getRandomLeaf(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getZmax().hasBuilding) {
            for (int z = 15 - info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS; z < 15; z++) {
                for (int x = 0; x < 16; x++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = info.profile.CHANCE_OF_RANDOM_LEAFBLOCKS * (z - 14 + info.profile.THICKNESS_OF_RANDOM_LEAFBLOCKS);
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION_ZMAX) < v && cnt < 30) {
                        driver.add(getRandomLeaf(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
    }

    private void generateParkSection(ChunkGenContext ctx, BuildingInfo info, int height, boolean elevated) {
        ChunkDriver driver = ctx.driver;
        char street = ctx.street;
        BlockState b;
        boolean el00 = info.getXmin().getZmin().isElevatedParkSection();
        boolean el10 = info.getZmin().isElevatedParkSection();
        boolean el20 = info.getXmax().getZmin().isElevatedParkSection();
        boolean el01 = info.getXmin().isElevatedParkSection();
        boolean el21 = info.getXmax().isElevatedParkSection();
        boolean el02 = info.getXmin().getZmax().isElevatedParkSection();
        boolean el12 = info.getZmax().isElevatedParkSection();
        boolean el22 = info.getXmax().getZmax().isElevatedParkSection();
        CompiledPalette compiledPalette = info.getCompiledPalette();

        Character grassChar = info.getCityStyle().getGrassBlock();
        BlockState grassBlock = Blocks.GRASS_BLOCK.defaultBlockState();
        boolean parkBorder = info.getCityStyle().getParkBorder() != null ? info.getCityStyle().getParkBorder() : info.profile.PARK_BORDER;
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                // Resolved per column, at the block it will be written to.
                BlockState grass = (grassChar == null)
                        ? grassBlock
                        : ctx.paletteAt(compiledPalette, grassChar, x, height, z);
                if (x == 0 || x == 15 || z == 0 || z == 15) {
                    b = null;
                    if (elevated) {
                        if (x == 0 && z == 0) {
                            if (el01 && el00 && el10) {
                                b = grass;
                            }
                        } else if (x == 15 && z == 0) {
                            if (el21 && el20 && el10) {
                                b = grass;
                            }
                        } else if (x == 0 && z == 15) {
                            if (el01 && el02 && el12) {
                                b = grass;
                            }
                        } else if (x == 15 && z == 15) {
                            if (el12 && el22 && el21) {
                                b = grass;
                            }
                        } else if (x == 0) {
                            if (el01) {
                                b = grass;
                            }
                        } else if (x == 15) {
                            if (el21) {
                                b = grass;
                            }
                        } else if (z == 0) {
                            if (el10) {
                                b = grass;
                            }
                        } else if (z == 15) {
                            if (el12) {
                                b = grass;
                            }
                        }
                        if (b == null) {
                            b = parkBorder ? ctx.paletteAt(compiledPalette, street, x, height, z) : grass;
                        }
                    } else {
                        b = parkBorder ? ctx.paletteAt(compiledPalette, street, x, height, z) : grass;
                    }
                } else {
                    b = grass;
                }
                driver.current(x, height, z).block(b);
            }
        }
    }

    /**
     * The part family a chunk's road class draws from. A style that defines no tertiary family falls
     * back to its ordinary streets, which {@link dev.krona.urbex.worldgen.lost.cityassets.CityStyle}
     * handles; a chunk with no planned road (an open lot rendered as paving) also uses the ordinary
     * family, because that is the narrowest surface available.
     */
    private static StreetParts getStreetParts(BuildingInfo info) {
        return switch (info.getEffectiveRoadType()) {
            case PRIMARY -> info.getCityStyle().getLargeStreetParts();
            case TERTIARY -> info.getCityStyle().getTertiaryStreetParts();
            case SECONDARY, NONE -> info.getCityStyle().getStreetParts();
        };
    }

    /**
     * Whether the street part on {@code info} should reach the edge it shares with {@code adjacent}.
     *
     * <p>On a primary road only another primary counts as a road connection. A secondary or tertiary
     * street still meets the primary's surface - that is what the connector overlay is for - but it
     * must not turn the primary into a bend or a junction, which would aim its quartz centre line
     * down a minor street.
     *
     * <p>A bridge still connects, primary or not: a bridge carries the road onward, so ignoring it
     * would end the road in a kerb with the bridge starting a chunk later out of nothing.
     */
    private static boolean hasStreetPartConnection(BuildingInfo info, BuildingInfo adjacent, boolean bridgeConnection) {
        boolean roadConnection = BuildingInfo.hasRoadConnection(info, adjacent);
        if (info.isPrimaryRoad()) {
            return (roadConnection && adjacent.isPrimaryRoad()) || bridgeConnection;
        }
        return roadConnection || bridgeConnection;
    }

    /**
     * The whole chunk as one ramp. The stair part is authored rising towards {@code XMIN}, so the
     * direction of the higher edge is exactly its rotation.
     */
    private void generateStreetSlopeSection(ChunkGenContext ctx, BuildingInfo info, int height, Direction slopeDirection) {
        StreetParts parts = getStreetParts(info);
        // A style with no stair part has opted out of slopes the same way an empty connector list
        // opts out of connectors below: asset gaps degrade rather than crash. Without this guard,
        // getRandomPart would call List.get on an empty list and throw.
        if (parts.stair().isEmpty()) {
            return;
        }
        BuildingPart part = provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.stair()));
        if (part != null) {
            generatePart(ctx, info, part, slopeDirection.getRotation(), 0, height, 0, HardAirSetting.VOID);
        }
    }

    private void generateNormalStreetSection(ChunkGenContext ctx, BuildingInfo info, int height) {
        StreetParts parts = getStreetParts(info);
        boolean xmin = hasStreetPartConnection(info, info.getXmin(), info.getXmin().hasXBridge(provider) != null);
        boolean xmax = hasStreetPartConnection(info, info.getXmax(), info.getXmax().hasXBridge(provider) != null);
        boolean zmin = hasStreetPartConnection(info, info.getZmin(), info.getZmin().hasZBridge(provider) != null);
        boolean zmax = hasStreetPartConnection(info, info.getZmax(), info.getZmax().hasZBridge(provider) != null);
        int cnt = (xmin ? 1 : 0) + (xmax ? 1 : 0) + (zmin ? 1 : 0) + (zmax ? 1 : 0);
        Transform transform = Transform.ROTATE_NONE;
        // Each part-family list is checked for emptiness before getRandomPart touches it, the same
        // way parts.stair() and parts.connector() are guarded elsewhere: an empty list is a style
        // opting out of that part, and getRandomPart would otherwise call List.get on a nextInt(0)
        // and throw. A null part here is already handled below (no render, no connectors).
        BuildingPart part = switch (cnt) {
            case 0 -> parts.none().isEmpty() ? null
                    : provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.none()));
            case 1 -> {
                if (xmin) {
                } else if (xmax) {
                    transform = Transform.ROTATE_180;
                } else if (zmin) {
                    transform = Transform.ROTATE_90;
                } else {
                    transform = Transform.ROTATE_270;
                }
                yield parts.end().isEmpty() ? null
                        : provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.end()));
            }
            case 2 -> {
                if (xmin == xmax || zmin == zmax) {
                    if (xmin) {
                    } else if (xmax) {
                        transform = Transform.ROTATE_180;
                    } else if (zmin) {
                        transform = Transform.ROTATE_90;
                    } else {
                        transform = Transform.ROTATE_270;
                    }
                    yield parts.straight().isEmpty() ? null
                            : provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.straight()));
                } else {
                    if (xmin && zmin) {
                    } else if (xmin && zmax) {
                        transform = Transform.ROTATE_270;
                    } else if (xmax && zmin) {
                        transform = Transform.ROTATE_90;
                    } else {
                        transform = Transform.ROTATE_180;
                    }
                    yield parts.bend().isEmpty() ? null
                            : provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.bend()));
                }
            }
            case 3 -> {
                if (!xmin) {
                    transform = Transform.ROTATE_90;
                } else if (!xmax) {
                    transform = Transform.ROTATE_270;
                } else if (!zmin) {
                    transform = Transform.ROTATE_180;
                }
                yield parts.t().isEmpty() ? null
                        : provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.t()));
            }
            case 4 -> parts.all().isEmpty() ? null
                    : provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.all()));
            default -> throw new RuntimeException("Not possible!");
        };
        if (part != null) {
            generatePart(ctx, info, part, transform, 0, height, 0, HardAirSetting.VOID);
            generateMinorStreetConnectors(ctx, info, parts, height);
        }
    }

    /**
     * Where a minor street runs up against a primary road, overlay a connector on the primary so the
     * two surfaces meet instead of ending in a kerb. Only primaries carry these - a minor street
     * meeting another minor street is an ordinary junction the topology already covers. A style with
     * an empty connector list has opted out; that is a choice, not a missing asset, so no warning.
     */
    private void generateMinorStreetConnectors(ChunkGenContext ctx, BuildingInfo info, StreetParts parts, int height) {
        if (!info.isPrimaryRoad() || parts.connector().isEmpty()) {
            return;
        }
        generateMinorStreetConnector(ctx, info, info.getXmin(), parts, height, Transform.ROTATE_NONE);
        generateMinorStreetConnector(ctx, info, info.getXmax(), parts, height, Transform.ROTATE_180);
        generateMinorStreetConnector(ctx, info, info.getZmin(), parts, height, Transform.ROTATE_90);
        generateMinorStreetConnector(ctx, info, info.getZmax(), parts, height, Transform.ROTATE_270);
    }

    private void generateMinorStreetConnector(ChunkGenContext ctx, BuildingInfo info, BuildingInfo adjacent,
                                              StreetParts parts, int height, Transform transform) {
        if (BuildingInfo.hasRoadConnection(info, adjacent) && !adjacent.isPrimaryRoad()) {
            BuildingPart connector = provider.assets().parts().getOrWarn(getRandomPart(ctx, parts.connector()));
            if (connector != null) {
                generatePart(ctx, info, connector, transform, 0, height, 0, HardAirSetting.VOID);
            }
        }
    }

    private boolean borderNeedsConnectionToAdjacentChunk(BuildingInfo info, int x, int z) {
        for (Direction direction : Direction.VALUES) {
            if (direction.atSide(x, z)) {
                BuildingInfo adjacent = direction.get(info);
                // A lower neighbour sloping up towards this chunk needs the retaining wall opened,
                // but only across the ramp itself: the stair part's z1/z2 band, rotated to this
                // edge. The rest of the wall stays, or the level change would read as a gap.
                if (adjacent.getStreetSlopeDirection() == direction.getOpposite()) {
                    StreetParts slopeParts = getStreetParts(adjacent);
                    if (!slopeParts.stair().isEmpty()) {
                        BuildingPart slope = provider.assets().parts().getOrWarn(slopeParts.stair().get(0));
                        if (slope != null) {
                            Integer z1 = slope.getMetaInteger(BuildingPart.META_Z_1);
                            Integer z2 = slope.getMetaInteger(BuildingPart.META_Z_2);
                            if (z1 != null && z2 != null) {
                                Transform transform = direction.getOpposite().getRotation();
                                int xx1 = transform.rotateX(15, z1);
                                int zz1 = transform.rotateZ(15, z1);
                                int xx2 = transform.rotateX(15, z2);
                                int zz2 = transform.rotateZ(15, z2);
                                if (x >= Math.min(xx1, xx2) && x <= Math.max(xx1, xx2)
                                        && z >= Math.min(zz1, zz2) && z <= Math.max(zz1, zz2)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                if (adjacent.getActualStairDirection() == direction.getOpposite()) {
                    BuildingPart stairType = adjacent.stairType;
                    if (stairType != null) {
                        Integer z1 = stairType.getMetaInteger(BuildingPart.META_Z_1);
                        Integer z2 = stairType.getMetaInteger(BuildingPart.META_Z_2);
                        Transform transform = direction.getOpposite().getRotation();
                        int xx1 = transform.rotateX(15, z1);
                        int zz1 = transform.rotateZ(15, z1);
                        int xx2 = transform.rotateX(15, z2);
                        int zz2 = transform.rotateZ(15, z2);
                        if (x >= Math.min(xx1, xx2) && x <= Math.max(xx1, xx2) && z >= Math.min(zz1, zz2) && z <= Math.max(zz1, zz2)) {
                            return true;
                        }
                    }
                }
                if (adjacent.hasBridge(provider, direction.getOrientation()) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    public enum HardAirSetting {
        AIR, WATERLEVEL, VOID
    }

    /**
     * Generate a part. If 'airWaterLevel' is true then 'hard air' blocks are replaced with water below the waterLevel.
     * Otherwise they are replaced with air.
     */
    public int generatePart(ChunkGenContext ctx, BuildingInfo info, IBuildingPart part,
                             Transform transform,
                             int ox, int oy, int oz, HardAirSetting airWaterLevel) {
        ChunkDriver driver = ctx.driver;
        if (profile.EDITMODE) {
            EditModeData.getData().addPartData(info.coord, oy, part.getName());
        }
        CompiledPalette compiledPalette = computePalette(info, part);

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
                            b = transformBlockState(ctx.tags, info, transform, b);
                        }

                        // We don't replace the world where the part is empty (air)
                        if (b != air) {
                            if (b == liquid) {
                                if (info.profile.AVOID_WATER) {
                                    b = air;
                                }
                            } else if (b == hardAir) {
                                switch (airWaterLevel) {
                                    case AIR:
                                        b = air;
                                        break;
                                    case WATERLEVEL:
                                        if (!info.profile.AVOID_FOLIAGE && !nowater && oy + y < info.waterLevel) {
                                            b = liquid;
                                        } else {
                                            b = air;
                                        }
                                        break;
                                    case VOID:
                                        // hardAir (STRUCTURE_VOID) is replaced by whatever was already there
                                        break;
                                }
                            } else if (inf != null) {
                                if (inf.light() != null || inf.isTorch()) {
                                    b = handleLightMarker(ctx, inf, driver.getCurrentCopy());
                                } else if (inf.loot() != null && !inf.loot().isEmpty()) {
                                    handleLoot(ctx, info, part, b, inf);
                                } else if (inf.mobId() != null && !inf.mobId().isEmpty()) {
                                    // ctx.region, not provider.getWorld(): these write block entity
                                    // NBT into a chunk, which only the generating region has.
                                    b = handleSpawner(ctx, info, part, oy, ctx.region, rx, rz, y, b, inf);
                                } else if (inf.tag() != null) {
                                    b = handleBlockEntity(ctx, info, oy, ctx.region, rx, rz, y, b, inf);
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
                                updateNeeded(ctx, driver.getCurrentCopy(), Block.UPDATE_CLIENTS);
                            } else if (ctx.tags.needsTodo(b)) {
                                b = handleTodo(ctx, info, oy, ctx.region, rx, rz, y, b);
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

    public BlockState handleLightMarker(ChunkGenContext ctx, Palette.Info marker, BlockPos pos) {
        if (DensitySelector.lighting(ctx.seed, pos, ctx.info.profile.LIGHTING_DENSITY)) {
            ctx.addLightTodo(pos, marker.light());
        }
        return air;
    }

    public CompiledPalette computePalette(BuildingInfo info, IBuildingPart part) {
        CompiledPalette compiledPalette = info.getCompiledPalette();
        // Cache the combined palette?
        Palette partPalette = part.getLocalPalette();
        if (partPalette != null) {
            compiledPalette = new CompiledPalette(compiledPalette, partPalette);
        }
        return compiledPalette;
    }

    private BlockEntityType getTypeForBlock(BlockState state) {
        // get / compute-outside / putIfAbsent, not computeIfAbsent: the registry walk used to
        // run inside a ConcurrentHashMap bin lock, stalling every other worldgen thread whose
        // block hashed into the same bin (issue #25). Racing threads compute the same answer.
        Block block = state.getBlock();
        BlockEntityType existing = typeCache.get(block);
        if (existing != null) {
            return existing;
        }
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (type.isValid(state)) {
                BlockEntityType raced = typeCache.putIfAbsent(block, type);
                return raced != null ? raced : type;
            }
        }
        return null;
    }

    private BlockState handleBlockEntity(ChunkGenContext ctx, BuildingInfo info, int oy, WorldGenLevel world, int rx, int rz, int y, BlockState b, Palette.Info inf) {
        BlockPos pos = info.getRelativePos(rx, oy + y, rz);
        BlockEntityType type = getTypeForBlock(b);
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
     * What is left is a spawner queued onto the air that replaced it. Minecraft
     * discovers this when the chunk is saved or promoted, logs "Invalid block entity"
     * with a full stack trace, and throws the data away anyway — so dropping it here
     * changes nothing about the world and removes the noise from the log.
     */
    private static void forgetOverwrittenBlockEntities(ChunkAccess chunk) {
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

    private BlockState handleSpawner(ChunkGenContext ctx, BuildingInfo info, IBuildingPart part, int oy, WorldGenLevel world, int rx, int rz, int y, BlockState b, Palette.Info inf) {
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
            RandomSource spawnerRandom = Rng.atPos(provider.getSeed(), pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.SPAWNERS);
            Identifier randomValue = getRandomSpawnerMob(world.getLevel(), spawnerRandom, provider, info,
                    new BuildingInfo.ConditionTodo(mobid, part.getName(), info), pos);
            CompoundTag sd = new CompoundTag();
            sd.putString("id", randomValue.toString());
            SpawnData data = new SpawnData(sd, Optional.empty(), Optional.empty());
            tag.put("SpawnData", SpawnData.CODEC.encodeStart(NbtOps.INSTANCE, data).result().orElseThrow(() -> new IllegalStateException("Invalid SpawnData")));

            world.getChunk(pos).setBlockEntityNbt(tag);
        } else {
            b = air;
        }
        return b;
    }

    private void handleLoot(ChunkGenContext ctx, BuildingInfo info, IBuildingPart part,
                            BlockState block, Palette.Info marker) {
        BlockPos pos = ctx.driver.getCurrentCopy();
        if (!SpecialMarkerPolicy.populateLoot(provider.getSeed(), pos, info.profile)) {
            return;
        }
        ctx.addPostTodo(pos, inWorld -> {
            if (!inWorld.getBlockState(pos).isAir()) {
                inWorld.setBlock(pos, block, Block.UPDATE_CLIENTS);
                generateLoot(info, inWorld, pos,
                        new BuildingInfo.ConditionTodo(marker.loot(), part.getName(), info));
            }
        });
    }

    private BlockState handleTodo(ChunkGenContext ctx, BuildingInfo info, int oy, WorldGenLevel world, int rx, int rz, int y, BlockState b) {
        Block block = b.getBlock();
        CityStyle cs = info.getCityStyle();
        boolean avoidFoliage = info.profile.AVOID_FOLIAGE;
        if (cs.getAvoidFoliage() != null) {
            avoidFoliage = cs.getAvoidFoliage();
        }
        if (block instanceof SaplingBlock || block instanceof FlowerBlock) {
            if (avoidFoliage) {
                b = air;
            } else {
                BlockPos pos = info.getRelativePos(rx, oy + y, rz);
                if (block instanceof SaplingBlock saplingBlock) {
                    BlockState finalB = b;
                    if (Config.FORCE_SAPLING_GROWTH.get()) {
                        // The todo runs later, on the server thread, long after this context is gone.
                        // Key the tree it grows on the sapling's position so it is the same tree no
                        // matter when the todo is drained.
                        RandomSource growthRandom = Rng.atPos(provider.getSeed(), pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.VEGETATION_GROWTH);
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
     * {@link BuildingInfo#worldStyle()} memoises it per chunk, so this stays a field read in the hot
     * path rather than a neighbourhood walk. A world style that declares no {@code rotatable}
     * resolves {@code urbex:rotatable}, as before.
     * <p>
     * Membership is answered by the chunk's own {@link TagSnapshot} rather than by a live registry
     * read, so every block of every part in this chunk sees one tag epoch even if a {@code /reload}
     * lands halfway through it (issue #128).
     */
    private BlockState transformBlockState(TagSnapshot tags, BuildingInfo info, Transform transform, BlockState b) {
        if (tags.isRotatable(info.worldStyle().getRotatableTag(), b)) {
            // Vanilla structure order: mirror first, then rotate. The mirror used to be
            // approximated with a 180/90 rotation, which turned mirrored stairs/doors/logs
            // the wrong way (issue #45).
            b = b.mirror(transform.getMcMirror()).rotate(transform.getMcRotation());
        } else if (getRailStates().contains(b)) {
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


    public static Identifier getRandomSpawnerMob(Level world, RandomSource random, IDimensionInfo diminfo, BuildingInfo info, BuildingInfo.ConditionTodo todo, BlockPos pos) {
        String condition = todo.getCondition();
        Condition cnd = diminfo.assets().conditions().getOrThrow(condition);
        int level = (pos.getY() - diminfo.getProfile().GROUNDLEVEL) / FLOORHEIGHT;
        int floor = (pos.getY() - info.getCityGroundLevel()) / FLOORHEIGHT;
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


    private void generateLoot(BuildingInfo info, LevelAccessor world, BlockPos pos, BuildingInfo.ConditionTodo condition) {
        BlockEntity te = world.getBlockEntity(pos);
        if (te instanceof RandomizableContainerBlockEntity) {
            // Runs from a post-todo, after generation of this chunk has finished, so it cannot
            // borrow the context's streams. The chest's own position addresses it instead.
            RandomSource lootRandom = Rng.atPos(provider.getSeed(), pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.LOOT);
            createLoot(info, lootRandom, world, pos, condition, this.provider);
        } else if (te == null) {
            ModSetup.getLogger().error("Error setting loot at {},{},{}", pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public static void createLoot(BuildingInfo info, RandomSource random, LevelAccessor world, BlockPos pos, BuildingInfo.ConditionTodo todo, IDimensionInfo diminfo) {
        BlockEntity tileentity = world.getBlockEntity(pos);
        if (tileentity instanceof RandomizableContainerBlockEntity rcbe) {
            if (todo != null) {
                String lootTable = todo.getCondition();
                int level = (pos.getY() - diminfo.getProfile().GROUNDLEVEL) / FLOORHEIGHT;
                int floor = (pos.getY() - info.getCityGroundLevel()) / FLOORHEIGHT;
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


    private void generateDebris(ChunkGenContext ctx, BuildingInfo info) {
        // One stream for all eight neighbours: a fresh one per direction would scatter the debris
        // from every one of them onto the same sixteen coordinates.
        RandomSource debrisRandom = ctx.rng(Rng.Purpose.DEBRIS);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getXmin(), (xx, zz) -> (15.0f - xx) / 16.0f);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getXmax(), (xx, zz) -> xx / 16.0f);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getZmin(), (xx, zz) -> (15.0f - zz) / 16.0f);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getZmax(), (xx, zz) -> zz / 16.0f);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getXmin().getZmin(), (xx, zz) -> ((15.0f - xx) * (15.0f - zz)) / 256.0f);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getXmax().getZmax(), (xx, zz) -> (xx * zz) / 256.0f);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getXmin().getZmax(), (xx, zz) -> ((15.0f - xx) * zz) / 256.0f);
        generateDebrisFromChunk(ctx, debrisRandom, info, info.getXmax().getZmin(), (xx, zz) -> (xx * (15.0f - zz)) / 256.0f);
    }

    private void generateDebrisFromChunk(ChunkGenContext ctx, RandomSource debrisRandom, BuildingInfo info, BuildingInfo adjacentInfo, BiFunction<Integer, Integer, Float> locationFactor) {
        ChunkDriver driver = ctx.driver;
        if (adjacentInfo.hasBuilding) {
            CompiledPalette adjacentPalette = adjacentInfo.getCompiledPalette();
            Character rubbleBlock = adjacentInfo.getBuilding().getRubbleBlock();
            if (!adjacentPalette.isDefined(rubbleBlock)) {
                rubbleBlock = adjacentInfo.getBuilding().getFillerBlock();
            }
            float damageFactor = adjacentInfo.getDamageArea().getDamageFactor();
            if (damageFactor > .5f) {
                // An estimate of the amount of blocks
                int blocks = (1 + adjacentInfo.getNumFloors()) * 1000;
                float damage = Math.max(1.0f, damageFactor * DamageArea.BLOCK_DAMAGE_CHANCE);
                int destroyedBlocks = (int) (blocks * damage);
                // How many go this direction (approx, based on cardinal directions from building as well as number that simply fall down)
                destroyedBlocks /= info.profile.DEBRIS_TO_NEARBYCHUNK_FACTOR;
                int startHeight = adjacentInfo.getMaxHeight() + 10;
                int maxBuildHeight = info.provider.getWorld().getMaxY() + 1;
                int minBuildHeight = info.provider.getWorld().getMinY();
                if (startHeight > maxBuildHeight - 1) {
                    // Clamp to the top of the world: the old code clamped an out-of-range start
                    // to minBuildHeight - 1, dropping debris at bedrock and reading below minY
                    startHeight = maxBuildHeight - 1;
                }

                CompiledPalette palette = info.getCompiledPalette();
                BlockState ironbarsState = Blocks.IRON_BARS.defaultBlockState();
                Character infobarsChar = info.getCityStyle().getIronbarsBlock();

                for (int i = 0; i < destroyedBlocks; i++) {
                    int x = debrisRandom.nextInt(16);
                    int z = debrisRandom.nextInt(16);
                    if (debrisRandom.nextFloat() < locationFactor.apply(x, z)) {
                        // Fresh drop per debris block: h used to persist across iterations, so
                        // after the first block sank, every later one started at its level and
                        // debris piled at one height instead of following the surface (issue #42)
                        int h = startHeight;
                        driver.current(x, h, z);
                        while (h > minBuildHeight && isEmpty(driver.getBlock())) {
                            h--;
                            driver.decY();
                        }
                        // Fix for FLOATING // @todo!
                        BlockState b;
                        if (debrisRandom.nextInt(5) == 0) {
                            b = infobarsChar == null ? ironbarsState : ctx.paletteAt(palette, infobarsChar, x, h + 1, z);
                        } else {
                            b = ctx.paletteAt(adjacentPalette, rubbleBlock, x, h + 1, z);     // Filler from adjacent building
                        }
                        driver.current(x, h + 1, z).block(b);
                    }
                }
            }
        }
    }

    private boolean doBorder(BuildingInfo info, Direction direction) {
        BuildingInfo adjacent = direction.get(info);
        if (isHigherThenNearbyStreetChunk(info, adjacent)) {
            return true;
        } else if (!adjacent.isCity) {
            if (adjacent.cityLevel <= info.cityLevel) {
                return true;
            }
        }
        return false;
    }

    private boolean isHigherThenNearbyStreetChunk(BuildingInfo info, BuildingInfo adjacent) {
        if (!adjacent.isCity) {
            return false;
        }
        if (adjacent.hasBuilding) {
            return adjacent.cityLevel + adjacent.getNumFloors() < info.cityLevel;
        } else {
            return adjacent.cityLevel < info.cityLevel;
        }
    }

    private void setBlocksFromPalette(ChunkGenContext ctx, int x, int y, int z, int y2, CompiledPalette palette, char character) {
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

    private void generateBuilding(ChunkGenContext ctx, BuildingInfo info, ChunkHeightmap heightmap, ChunkAccess chunk) {
        ChunkDriver driver = ctx.driver;
        int lowestLevel = info.getBuildingBottomHeight();
        int cellars = info.cellars;
        int floors = info.getNumFloors();
        int max = info.provider.getWorld().getMaxY() - 1 - FLOORHEIGHT;

        CompiledPalette palette = info.getCompiledPalette();
        makeRoomForBuilding(ctx, info, lowestLevel, heightmap, palette);

        char fillerBlock = info.getBuilding().getFillerBlock();
        List<Pair<Integer, BuildingPart>> part2Map = new ArrayList<>();

        int height = lowestLevel;
        for (int f = -cellars; f <= floors; f++) {
            // In default landscape type we clear the landscape on top of the building when we are at the top floor
            if (f == floors) {
                if (profile.isDefault()) {
                    clearToMax(ctx, info, heightmap, height, max);
                }
            }

            BuildingPart part = info.getFloor(f);
            generatePart(ctx, info, part, Transform.ROTATE_NONE, 0, height, 0, HardAirSetting.AIR);
            part = info.getFloorPart2(f);
            if (part != null) {
                part2Map.add(Pair.of(height, part));
            }

            // Check for doors
            boolean isTop = f == floors;   // The top does not need generated doors
            if (!isTop && info.getAllowDoors()) {
                Doors.generateDoors(ctx, this, info, height + 1, f);
            }

            height += FLOORHEIGHT;    // We currently only support 6 here
        }

        if (cellars > 0 && info.getAllowFillers()) {
            // Underground we replace the glass with the filler
            for (int x = 0; x < 16; x++) {
                // Use safe version because this may end up being lower
                setBlocksFromPalette(ctx, x, lowestLevel, 0, Math.min(info.getCityGroundLevel(), info.getZmin().getCityGroundLevel()) + 1, palette, fillerBlock);
                setBlocksFromPalette(ctx, x, lowestLevel, 15, Math.min(info.getCityGroundLevel(), info.getZmax().getCityGroundLevel()) + 1, palette, fillerBlock);
            }
            for (int z = 1; z < 15; z++) {
                setBlocksFromPalette(ctx, 0, lowestLevel, z, Math.min(info.getCityGroundLevel(), info.getXmin().getCityGroundLevel()) + 1, palette, fillerBlock);
                setBlocksFromPalette(ctx, 15, lowestLevel, z, Math.min(info.getCityGroundLevel(), info.getXmax().getCityGroundLevel()) + 1, palette, fillerBlock);
            }
        }

        if (cellars >= 1) {
            // We have to potentially connect to corridors
            Corridors.generateCorridorConnections(driver, info);
        }

        if (!part2Map.isEmpty()) {
            // Commit what exists so the part2 floors can see it; corrections, heightmaps and
            // the write-recorder publish all stay for the real end of generation (issue #48)
            driver.flushToChunk(chunk);
            for (Pair<Integer, BuildingPart> entry : part2Map) {
                int h = entry.getKey();
                BuildingPart part = entry.getValue();
                generatePart(ctx, info, part, Transform.ROTATE_NONE, 0, h, 0, HardAirSetting.AIR);
            }
        }
    }

    /*
     * Make sure the space for the building is cleared and everything below the building is ok
     */
    private void makeRoomForBuilding(ChunkGenContext ctx, BuildingInfo info, int lowestLevel, ChunkHeightmap heightmap, CompiledPalette palette) {
        ChunkDriver driver = ctx.driver;
        char borderBlock = info.getCityStyle().getBorderBlock();
        char fillerBlock = info.getBuilding().getFillerBlock();

        if (info.profile.isFloating()) {
            // For floating worldgen we try to fit the underside of the building better with the island
            // We also remove all blocks from the inside because we generate buildings on top of
            // generated chunks as opposed to blank chunks with non-floating worlds
            double[] bottomLayerBuffer = ctx.buffers.bottomLayer = this.bottomLayerNoise.getRegion(ctx.buffers.bottomLayer, (info.coord.chunkX() << 4), (info.coord.chunkZ() << 4), 16, 16, 8.0 / 16.0, 8.0 / 16.0, 1.0D);
            int minBuildHeight = info.provider.getWorld().getMinY();
            int maxBuildHeight = info.provider.getWorld().getMaxY() + 1;
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    double vr = bottomLayerBuffer[x + z * 16] / 4.0f;
                    driver.current(x, maxBuildHeight - 1, z);
                    int minHeight = minBuildHeight;
                    int lowestToFill = Math.max(minHeight, lowestLevel - 6 - (int) vr);
                    while (driver.getBlock() == air && driver.getY() > lowestToFill) {
                        driver.decY();
                    }

                    int height = driver.getY();//heightmap.getHeight(x, z);
                    if (height > minHeight + 1 && height < lowestLevel - 1) {
                        driver.setBlockRange(x, height + 1, z, lowestLevel, base);
                    }
                    // Also clear the inside of buildings to avoid geometry that doesn't really belong there
                    clearRange(ctx, info, x, z, lowestLevel, info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT, info.waterLevel > info.groundLevel);
                }
            }
        } else if (info.profile.isCavern()) {
            // For normal cavern we have a thin layer of 'border' blocks because that looks nicer
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    if (isSide(x, z)) {
                        setBlocksFromPalette(ctx, x, lowestLevel - 10, z, lowestLevel, palette, borderBlock);
                    }
                    if (driver.getBlock(x, lowestLevel, z) == air) {
                        BlockState filler = ctx.paletteAt(palette, fillerBlock, x, lowestLevel, z);
                        driver.current(x, lowestLevel, z).block(filler); // There is nothing below so we fill this with the filler
                    }

                    // Also clear the inside of buildings to avoid geometry that doesn't really belong there
                    clearRange(ctx, info, x, z, lowestLevel, info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT, info.waterLevel > info.groundLevel);
                }
            }
        } else {
            // For normal worldgen we have a thin layer of 'border' blocks because that looks nicer
            // We try to avoid this layer in big caves though
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    if (isSide(x, z)) {
                        int y = getMinHeightAt(info, x, z, heightmap);
                        if (y >= lowestLevel) {
                            // The building generates below heightmap height. So we generate a border of 3 only
                            y = lowestLevel - 3;
                        }
                        setBlocksFromPalette(ctx, x, y, z, lowestLevel, palette, borderBlock);
                    }
                    if (driver.getBlock(x, lowestLevel, z) == air) {
                        BlockState filler = ctx.paletteAt(palette, fillerBlock, x, lowestLevel, z);
                        driver.current(x, lowestLevel, z).block(filler); // There is nothing below so we fill this with the filler
                    }
                    // That single filler block is not enough when the bottom of the building ends
                    // up over a cave, a ravine or a terrain step: give it ground to stand on.
                    fillSupportBelow(ctx, x, z, lowestLevel - 1);

                    // Also clear the inside of buildings to avoid geometry that doesn't really belong there
                    clearRange(ctx, info, x, z, lowestLevel, info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT, info.waterLevel > info.groundLevel);
                }
            }
        }
    }

    private void clearToMax(ChunkGenContext ctx, BuildingInfo info, ChunkHeightmap heightmap, int height, int max) {
        int maximumHeight = Math.min(max, heightmap.getHeight() + 10);
        if (height < maximumHeight) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    clearRange(ctx, info, x, z, height, maximumHeight, false);
                }
            }
        }
    }

    private static boolean isSide(int x, int z) {
        return x == 0 || x == 15 || z == 0 || z == 15;
    }

    private static boolean isCorner(int x, int z) {
        return (x == 0 || x == 15) && (z == 0 || z == 15);
    }

    /**
     * Queue a place-twice refresh at {@code pos} on the context generating this chunk.
     * <p>
     * Takes the context rather than the chunk's {@code BuildingInfo}: the refresh belongs to one
     * generation, and a cached planning value is not allowed to hold it (issue #127).
     */
    public static void updateNeeded(ChunkGenContext ctx, BlockPos pos, int flags) {
        ctx.addPostTodo(pos, world -> {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir()) {
                world.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
                world.setBlock(pos, state, flags);
            }
        });
    }

}
