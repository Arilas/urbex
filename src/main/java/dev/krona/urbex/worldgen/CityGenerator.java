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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CityGenerator {

    public static final int FLOORHEIGHT = 6;

    public final BlockState air;
    public final BlockState hardAir;

    private final BlockState base;
    public final BlockState liquid;

    // Built on first use and cached on this object, which is shared by the whole dimension. That was
    // safe only because generation held a lock on this feature. It is a pure function of two vanilla
    // blocks - no Level, no tag, no datapack - so it is built once in the constructor and never
    // written again. The three fields that used to sit beside it were block-tag expansions and have
    // moved to TagSnapshot, so a /reload can refresh them without rebuilding this generator and
    // everything the level plans with (issue #128); this one has nothing to reload.
    private final Set<BlockState> railStates;

    private final NoiseGeneratorPerlin bottomLayerNoise;    // Used in floating profile for the underside of buildings


    /** The random leaf and rubble tables, and the city-style characters that override them. */
    public final GroundCover groundCover = new GroundCover();

    /** The rubble, ruin and vegetation passes, and the three noise fields they read. */
    public final Decorations decorations;

    public final PlanningContext provider;
    public final Preset profile;

    private final Statistics statistics = new Statistics();
    public CityGenerator(PlanningContext provider, Preset profile) {
        this.provider = provider;
        this.profile = profile;
//        int waterLevel = provider.getWorld() == null ? 65 : Tools.getSeaLevel(provider.getWorld());// profile.groundLevel() - profile.WATERLEVEL_OFFSET;
        // Four independent noise fields, each seeded from the world seed alone. They describe the
        // whole dimension rather than one chunk, so they take a fixed coordinate and are built once.
        long seed = provider.seed();
        this.decorations = new Decorations(seed);
        this.bottomLayerNoise = new NoiseGeneratorPerlin(Rng.at(seed, 3, 0, Rng.Purpose.NOISE), 4);

        air = Blocks.AIR.defaultBlockState();
        hardAir = Blocks.STRUCTURE_VOID.defaultBlockState();
        base = profile.getBaseBlock();
        liquid = profile.getLiquidBlock();

        railStates = new HashSet<>();
        addStates(Blocks.RAIL, railStates);
        addStates(Blocks.POWERED_RAIL, railStates);

//        islandTerrainGenerator.setup(provider.getWorld().getWorld(), provider);
//        cavernTerrainGenerator.setup(provider.getWorld().getWorld(), provider);
//        spaceTerrainGenerator.setup(provider.getWorld().getWorld(), provider);
    }

    public Set<BlockState> getRailStates() {
        return railStates;
    }

    private static void addStates(Block block, Set<BlockState> set) {
        set.addAll(block.getStateDefinition().getPossibleStates());
    }

    private boolean isVoid(ChunkGenContext ctx, int x, int z) {
        ChunkDriver driver = ctx.driver;
        driver.current(x, provider.shape().maxY(), z);
        int minHeight = provider.shape().minY();
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
        ChunkCoord coord = new ChunkCoord(provider.dimension(), chunk.getPos().x(), chunk.getPos().z());
        try {
            generateOrThrow(runtime, region, chunk, coord);
        } catch (Throwable t) {
            // One category, one outcome: the chunk is not generated, so it must not continue through
            // the pipeline and be saved as though it were (issue #131). What used to happen here was
            // a log line and a return of success.
            //
            // generateOrThrow attaches the commit state, because only it can see the driver. Anything
            // that arrives here unattached failed before the driver existed, which is the same thing
            // as having written nothing.
            ChunkGenerationFailure failure = t instanceof ChunkGenerationFailure attached
                    ? attached
                    : new ChunkGenerationFailure(coord, ChunkDriver.CommitState.BUFFERED, t);
            Urbex.getLogger().error(failure.getMessage(), failure.getCause());
            ErrorLogger.logChunkInfo(coord.chunkX(), coord.chunkZ(), provider);
            // A non-fatal diagnostic in its own right: the chunk fails whether or not anyone is
            // listening, and this only tells whoever is playing to go and read the log.
            ErrorLogger.report(runtime.level().getServer(),
                    "There was an error generating a chunk. See log for details!");
            throw failure;
        }
    }

    /**
     * The generation itself, with the commit state attached to whatever it throws.
     * <p>
     * Only this method can see the driver, and the driver is the only thing that knows whether a
     * failure landed before, during or after the buffered blocks were written to the world - see
     * {@link ChunkDriver.CommitState}.
     */
    private void generateOrThrow(DimensionRuntime runtime, WorldGenRegion region,
                                 ChunkAccess chunk, ChunkCoord coord) {
        long start = System.currentTimeMillis();
        // One ordinal for this chunk, claimed before anything is measured, so the warm-up exclusion
        // reaches the same verdict for both phases and for the chunk itself (issue #132).
        long ordinal = GenerationMetrics.beginChunk();
        long startNanos = GenerationMetrics.enabled() ? System.nanoTime() : 0;
        long planAlloc = GenerationMetrics.allocMark();

        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();

        // A copy, because correctTerrainShape() below writes the corrected surface back into it.
        // The instance in caches().heightmap is shared with every other thread generating a chunk
        // near this one and must stay what getHeightmap() promises it is: a pure function of the
        // generator and the coordinate. Writing the correction into it instead would publish a
        // value whose presence depends on whether this chunk ran before or after its neighbour
        // read it - the neighbour's border height, and so the terrain, would depend on generation
        // order. See Arilas/urbex#24.
        ChunkHeightmap heightmap = new ChunkHeightmap(provider.heightmap(coord));
        ChunkPlan info = ChunkPlan.getChunkPlan(coord, provider);
        // The planning/building boundary, and the only place in this method it exists. Everything
        // above is a pure function of the seed and the coordinate - the heightmap comes from
        // TerrainSampler, which reads no block, and the plan from caches DimensionCaches documents
        // as recomputable by any thread - so it is the half that could in principle be computed
        // before this chunk was ever asked for. Everything below needs this region and this chunk.
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.PLAN, startNanos, planAlloc);
        long buildNanos = GenerationMetrics.mark();
        long buildAlloc = GenerationMetrics.allocMark();
        // runtime.tags() is read here and nowhere else in this generation: one call, at the start,
        // so a /reload landing mid-chunk cannot be observed halfway through a building (issue #128).
        ChunkGenContext ctx = new ChunkGenContext(region, chunk, coord, provider, profile, info,
                runtime.tasks(), runtime.tags());
        try {

        // Reused for each pass below rather than one pair of locals per pass: the passes are
        // strictly sequential, so a single mark that is closed and immediately retaken measures each
        // of them exactly once, and the alternative is eighteen names for the same two numbers.
        long phaseNanos = GenerationMetrics.mark();
        long phaseAlloc = GenerationMetrics.allocMark();

        boolean doCity = info.isCity;

        // Check if there is no village or other structure here. We don't do this for multibuildings because otherwise part of the multibuilding might be cut off
        AvoidChunk avoidChunk = AvoidChunk.NO;
        if (!info.multiBuildingPos.isMulti()) {
            avoidChunk = hasBlacklistedStructure(region, chunkX, chunkZ);
            if (avoidChunk != AvoidChunk.NO) {
                // Only this chunk's rendering. The cached ChunkPlan and ChunkCandidate used
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
        if (doCity && provider.preset().cityAvoidVoid() && provider.preset().isFloating()) {
            boolean v = isVoid(ctx, 2, 2) || isVoid(ctx, 2, 14) || isVoid(ctx, 14, 2) || isVoid(ctx, 14, 14) || isVoid(ctx, 8, 8);
            doCity = !v;
        }
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.PROBE, phaseNanos, phaseAlloc);

        phaseNanos = GenerationMetrics.mark();
        phaseAlloc = GenerationMetrics.allocMark();
        if (doCity) {
            doCityChunk(ctx, info, heightmap, chunk);
            GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.CITY, phaseNanos, phaseAlloc);
        } else {
            // We already have a prefilled core chunk (as generated from doCoreChunk)
            doNormalChunk(ctx, info, heightmap, avoidChunk);
            // Counted apart from CITY rather than together with it: the two are alternatives, and a
            // combined figure would average a city chunk with a field and describe neither. Their
            // sample counts also say how much of the window is city at all, which is the number that
            // decides whether a city-side saving is worth anything here.
            GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.TERRAIN, phaseNanos, phaseAlloc);
        }

        // Suppressed here rather than removed from the plan, the same way a village suppresses this
        // chunk's city above: a building deep enough to hit the line cancels the rails where they
        // would be drawn, and the neighbouring chunks keep planning and rendering the line as though
        // it ran through (issue #126, and see Railway.buildingBlocksRail for the precedence).
        phaseNanos = GenerationMetrics.mark();
        phaseAlloc = GenerationMetrics.allocMark();
        Railway.RailChunkInfo railInfo = Railway.buildingBlocksRail(coord, provider)
                ? Railway.RailChunkInfo.NOTHING
                : info.getRailInfo();
        if (railInfo.getType() != RailChunkType.NONE) {
            Railways.generateRailways(ctx, this, info, railInfo, heightmap);
        }
        Railways.generateRailwayDungeons(ctx, this, info);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.RAIL, phaseNanos, phaseAlloc);

        phaseNanos = GenerationMetrics.mark();
        phaseAlloc = GenerationMetrics.allocMark();
        placeOptionalLights(ctx, info);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.LIGHTS, phaseNanos, phaseAlloc);

        phaseNanos = GenerationMetrics.mark();
        phaseAlloc = GenerationMetrics.allocMark();
        if (info.getDamageArea().hasExplosions()) {
            Damage.breakBlocks(ctx, this, chunkX, chunkZ, info);
            Damage.fixFloatingBlocks(ctx, this, info);
        }
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.DAMAGE, phaseNanos, phaseAlloc);

        phaseNanos = GenerationMetrics.mark();
        phaseAlloc = GenerationMetrics.allocMark();
        generateDebris(ctx, info);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.DEBRIS, phaseNanos, phaseAlloc);

        phaseNanos = GenerationMetrics.mark();
        phaseAlloc = GenerationMetrics.allocMark();
        ctx.driver.actuallyGenerate(chunk, ordinal);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.COMMIT, phaseNanos, phaseAlloc);

        phaseNanos = GenerationMetrics.mark();
        phaseAlloc = GenerationMetrics.allocMark();
        ChunkFixer.fix(ctx);
        // After the fixer, so the post-todos have placed their blocks and what we see is final
        Parts.forgetBlockEntities(chunk);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.FIXER, phaseNanos, phaseAlloc);

        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.BUILD, buildNanos, buildAlloc);

        long time = System.currentTimeMillis() - start;
        statistics.addTime(time);
        // Nanoseconds, separately from the millisecond Statistics that /urbex stats reports: a
        // chunk taking under a millisecond rounds to zero there, which is most of them, and a tail
        // latency built out of those numbers would be made of zeroes (issue #132).
        GenerationMetrics.chunk(ordinal, System.nanoTime() - startNanos);
        } catch (Throwable t) {
            throw new ChunkGenerationFailure(coord, ctx.driver.commitState(), t);
        }
    }

    public Statistics getStatistics() {
        return statistics;
    }

    private int getTopLevel(ChunkPlan info) {
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
     * planning a question from generation - {@code ChunkPlan.isCity}, pure seed and coordinate -
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
        if (!Config.avoidVillages() && !Config.avoidSurfaceStructures()
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
                    if (Config.avoidVillages()
                            && key.map(k -> structures.getOrThrow(k).is(StructureTags.VILLAGE)).orElse(false)) {
                        return true;
                    }
                    // Catch-all for structure mods: everything that builds at the surface step
                    if (Config.avoidSurfaceStructures()
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
    public void placeOptionalLights(ChunkGenContext ctx, ChunkPlan info) {
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

    private void doNormalChunk(ChunkGenContext ctx, ChunkPlan info, ChunkHeightmap heightmap, AvoidChunk avoidChunk) {
//        debugClearChunk(chunkX, chunkZ, primer);
        if ((avoidChunk != AvoidChunk.YES || !Config.avoidFlattening()) && profile.isDefault()) {
            Terrain.correctTerrainShape(ctx, this, info.coord, heightmap);
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

    public String getRandomPart(ChunkGenContext ctx, List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        } else {
            return parts.get(ctx.rng(Rng.Purpose.PARTS).nextInt(parts.size()));
        }
    }

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
    private void doCityChunk(ChunkGenContext ctx, ChunkPlan info, ChunkHeightmap heightmap, ChunkAccess chunk) {
        ChunkDriver driver = ctx.driver;
        boolean building = info.hasBuilding;

        if (info.profile.isDefault()) {
            int minHeight = info.provider.shape().minY();
            BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    driver.setBlockRange(x, minHeight, z, minHeight + info.profile.bedrockLayer(), bedrock);
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
                    int maxTouchedY = Terrain.moveDown(ctx, this, x, z, ground + 1, provider.shape().maxBuildHeight());
                    if (maxTouchedY == Short.MIN_VALUE) {
                        Terrain.moveUp(ctx, this, x, z, ground, info.waterLevel > info.groundLevel);
                    }
                }
            }
        }

        if (building) {
            generateBuilding(ctx, info, heightmap, chunk);
        } else {
            Streets.generateStreet(ctx, this, info, heightmap);
        }

        if (info.profile.ruinChance() > 0.0) {
            decorations.ruins(ctx, this, info);
        }

        int levelX = info.getHighwayXLevel();
        int levelZ = info.getHighwayZLevel();
        if (!building) {
            Railway.RailChunkInfo railInfo = info.getRailInfo();
            if (levelX < 0 && levelZ < 0 && !railInfo.getType().isSurface()
                    && info.getStreetSlopeDirection() == null) {
                Streets.generateStreetDecorations(ctx, this, info);
            }
        }
        if (levelX >= 0 || levelZ >= 0) {
            Highways.generateHighways(ctx, this, info);
        }

        if (info.profile.rubbleLayer()) {
            if (!info.hasBuilding || info.ruinHeight >= 0) {
                decorations.rubble(ctx, this, info);
            }
        }

        Stuff.generateStuff(ctx, this, info);
    }

    public enum HardAirSetting {
        AIR, WATERLEVEL, VOID
    }

    private void generateDebris(ChunkGenContext ctx, ChunkPlan info) {
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

    private void generateDebrisFromChunk(ChunkGenContext ctx, RandomSource debrisRandom, ChunkPlan info, ChunkPlan adjacentInfo, BiFunction<Integer, Integer, Float> locationFactor) {
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
                destroyedBlocks /= info.profile.debrisToNearbyChunkFactor();
                int startHeight = adjacentInfo.getMaxHeight() + 10;
                int maxBuildHeight = info.provider.shape().maxBuildHeight();
                int minBuildHeight = info.provider.shape().minY();
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

    private void generateBuilding(ChunkGenContext ctx, ChunkPlan info, ChunkHeightmap heightmap, ChunkAccess chunk) {
        ChunkDriver driver = ctx.driver;
        int lowestLevel = info.getBuildingBottomHeight();
        int cellars = info.cellars;
        int floors = info.getNumFloors();
        int max = info.provider.shape().maxY() - 1 - FLOORHEIGHT;

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
            Parts.generatePart(ctx, this, info, part, Transform.ROTATE_NONE, 0, height, 0, HardAirSetting.AIR);
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
                Parts.setBlocksFromPalette(ctx, this, x, lowestLevel, 0, Math.min(info.getCityGroundLevel(), info.getZmin().getCityGroundLevel()) + 1, palette, fillerBlock);
                Parts.setBlocksFromPalette(ctx, this, x, lowestLevel, 15, Math.min(info.getCityGroundLevel(), info.getZmax().getCityGroundLevel()) + 1, palette, fillerBlock);
            }
            for (int z = 1; z < 15; z++) {
                Parts.setBlocksFromPalette(ctx, this, 0, lowestLevel, z, Math.min(info.getCityGroundLevel(), info.getXmin().getCityGroundLevel()) + 1, palette, fillerBlock);
                Parts.setBlocksFromPalette(ctx, this, 15, lowestLevel, z, Math.min(info.getCityGroundLevel(), info.getXmax().getCityGroundLevel()) + 1, palette, fillerBlock);
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
                Parts.generatePart(ctx, this, info, part, Transform.ROTATE_NONE, 0, h, 0, HardAirSetting.AIR);
            }
        }
    }

    /*
     * Make sure the space for the building is cleared and everything below the building is ok
     */
    private void makeRoomForBuilding(ChunkGenContext ctx, ChunkPlan info, int lowestLevel, ChunkHeightmap heightmap, CompiledPalette palette) {
        ChunkDriver driver = ctx.driver;
        char borderBlock = info.getCityStyle().getBorderBlock();
        char fillerBlock = info.getBuilding().getFillerBlock();

        if (info.profile.isFloating()) {
            // For floating worldgen we try to fit the underside of the building better with the island
            // We also remove all blocks from the inside because we generate buildings on top of
            // generated chunks as opposed to blank chunks with non-floating worlds
            double[] bottomLayerBuffer = ctx.buffers.bottomLayer = this.bottomLayerNoise.getRegion(ctx.buffers.bottomLayer, (info.coord.chunkX() << 4), (info.coord.chunkZ() << 4), 16, 16, 8.0 / 16.0, 8.0 / 16.0, 1.0D);
            int minBuildHeight = info.provider.shape().minY();
            int maxBuildHeight = info.provider.shape().maxBuildHeight();
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
                    Terrain.clearRange(ctx, this, info, x, z, lowestLevel, info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT, info.waterLevel > info.groundLevel);
                }
            }
        } else if (info.profile.isCavern()) {
            // For normal cavern we have a thin layer of 'border' blocks because that looks nicer
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    if (isSide(x, z)) {
                        Parts.setBlocksFromPalette(ctx, this, x, lowestLevel - 10, z, lowestLevel, palette, borderBlock);
                    }
                    if (driver.getBlock(x, lowestLevel, z) == air) {
                        BlockState filler = ctx.paletteAt(palette, fillerBlock, x, lowestLevel, z);
                        driver.current(x, lowestLevel, z).block(filler); // There is nothing below so we fill this with the filler
                    }

                    // Also clear the inside of buildings to avoid geometry that doesn't really belong there
                    Terrain.clearRange(ctx, this, info, x, z, lowestLevel, info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT, info.waterLevel > info.groundLevel);
                }
            }
        } else {
            // For normal worldgen we have a thin layer of 'border' blocks because that looks nicer
            // We try to avoid this layer in big caves though
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    if (isSide(x, z)) {
                        int y = Terrain.getMinHeightAt(this, info, x, z, heightmap);
                        if (y >= lowestLevel) {
                            // The building generates below heightmap height. So we generate a border of 3 only
                            y = lowestLevel - 3;
                        }
                        Parts.setBlocksFromPalette(ctx, this, x, y, z, lowestLevel, palette, borderBlock);
                    }
                    if (driver.getBlock(x, lowestLevel, z) == air) {
                        BlockState filler = ctx.paletteAt(palette, fillerBlock, x, lowestLevel, z);
                        driver.current(x, lowestLevel, z).block(filler); // There is nothing below so we fill this with the filler
                    }
                    // That single filler block is not enough when the bottom of the building ends
                    // up over a cave, a ravine or a terrain step: give it ground to stand on.
                    Terrain.fillSupportBelow(ctx, this, x, z, lowestLevel - 1);

                    // Also clear the inside of buildings to avoid geometry that doesn't really belong there
                    Terrain.clearRange(ctx, this, info, x, z, lowestLevel, info.getCityGroundLevel() + info.getNumFloors() * FLOORHEIGHT, info.waterLevel > info.groundLevel);
                }
            }
        }
    }

    public void clearToMax(ChunkGenContext ctx, ChunkPlan info, ChunkHeightmap heightmap, int height, int max) {
        int maximumHeight = Math.min(max, heightmap.getHeight() + 10);
        if (height < maximumHeight) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Terrain.clearRange(ctx, this, info, x, z, height, maximumHeight, false);
                }
            }
        }
    }

    private static boolean isSide(int x, int z) {
        return x == 0 || x == 15 || z == 0 || z == 15;
    }

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
